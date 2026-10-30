package com.exemplo.ativacao;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.exemplo.ativacao.core.application.port.in.*;
import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.Comando;
import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.Criada;
import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao;
import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao.*;
import com.exemplo.ativacao.support.PostgresTestConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ProcessarMensagemIT {
  @Autowired RegistrarSolicitacaoUseCase registrar;
  @Autowired ProcessarMensagemUseCase processar;
  @Autowired ReconciliarSolicitacoesUseCase reconciliar;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ProvedorAtivacao provedor;

  @BeforeEach
  void limpar() {
    jdbc.execute("truncate activation_inbox, activation_outbox, activation_request");
  }

  Mensagem criar() {
    var s =
        ((Criada)
                registrar.executar(
                    new Comando("cliente-a", UUID.randomUUID().toString(), "C-1", "P-1", "trace")))
            .solicitacao();
    return jdbc.queryForObject(
        "select * from activation_outbox where request_id = ?",
        (rs, n) ->
            new Mensagem(
                1,
                rs.getObject("event_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("client_id"),
                rs.getLong("request_version"),
                rs.getString("correlation_id")),
        s.id());
  }

  String status(Mensagem m) {
    return jdbc.queryForObject(
        "select status from activation_request where id = ?", String.class, m.requestId());
  }

  @Test
  void duplicacaoNaoRepeteHttpEHttpNaoSeguraTransacao() {
    var m = criar();
    when(provedor.ativar(any()))
        .thenAnswer(
            inv -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(status(m)).isEqualTo("PROCESSING");
              assertThat(
                      jdbc.queryForObject(
                          "select status from activation_inbox where event_id = ?",
                          String.class,
                          m.eventId()))
                  .isEqualTo("PROCESSING");
              return new Ativado();
            });
    processar.executar(m);
    processar.executar(m);
    assertThat(status(m)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select status from activation_inbox where event_id = ?",
                String.class,
                m.eventId()))
        .isEqualTo("COMPLETED");
    verify(provedor, times(1)).ativar(any());
  }

  @Test
  void retryConcluiInboxEAgendaNovoEventoRespeitandoRetryAfter() {
    var m = criar();
    when(provedor.ativar(any())).thenReturn(new Indisponivel("503", Duration.ofSeconds(30)));
    processar.executar(m);
    processar.executar(m);
    assertThat(status(m)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from activation_outbox where request_id = ?",
                Long.class,
                m.requestId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select extract(epoch from (next_attempt_at - updated_at))::integer from activation_request where id = ?",
                Integer.class,
                m.requestId()))
        .isEqualTo(30);
    verify(provedor, times(1)).ativar(any());
  }

  @Test
  void mensagemDeOutroClienteNaoAlteraSolicitacao() {
    var m = criar();
    processar.executar(
        new Mensagem(
            1, UUID.randomUUID(), m.requestId(), "outro-cliente", m.requestVersion(), "trace"));
    assertThat(status(m)).isEqualTo("PENDING");
    verifyNoInteractions(provedor);
  }

  @Test
  void workerAntigoNaoSobrescreveReconciliacao() {
    var m = criar();
    when(provedor.ativar(any()))
        .thenAnswer(
            inv -> {
              jdbc.update(
                  "update activation_request set updated_at = now() - interval '2 minutes' where id = ?",
                  m.requestId());
              assertThat(reconciliar.reconciliar()).isEqualTo(1);
              return new Ativado();
            });
    processar.executar(m);
    assertThat(status(m)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select status from activation_inbox where event_id = ?",
                String.class,
                m.eventId()))
        .isEqualTo("RECOVERED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from activation_outbox where request_id = ?",
                Long.class,
                m.requestId()))
        .isEqualTo(2);
  }

  @Test
  void falhaAoGravarOutboxFazRollbackDoDesfechoEInbox() {
    var m = criar();
    when(provedor.ativar(any()))
        .thenAnswer(
            inv -> {
              jdbc.execute(
                  "alter table activation_outbox add constraint teste_rejeitar_retry check (request_version = 0)");
              return new Indisponivel("503", null);
            });
    try {
      assertThatThrownBy(() -> processar.executar(m)).isInstanceOf(RuntimeException.class);
      assertThat(status(m)).isEqualTo("PROCESSING");
      assertThat(
              jdbc.queryForObject(
                  "select status from activation_inbox where event_id = ?",
                  String.class,
                  m.eventId()))
          .isEqualTo("PROCESSING");
    } finally {
      jdbc.execute("alter table activation_outbox drop constraint teste_rejeitar_retry");
    }
  }
}
