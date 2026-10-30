package com.exemplo.ativacao.infrastructure.persistence;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.port.in.ReconciliarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository.ConflitoDeVersaoException;
import com.exemplo.ativacao.core.application.port.out.Transacao;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.support.PostgresTestConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SolicitacaoRepositoryAdapterIT {
  @Autowired SolicitacaoRepository solicitacaoAdapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired Transacao transacao;
  @Autowired ReconciliarSolicitacoesUseCase reconciliar;
  final Instant t0 = Instant.parse("2026-10-26T14:03:11.123456Z");

  @BeforeEach
  void limpar() {
    jdbc.execute("truncate activation_inbox, activation_outbox, activation_request");
  }

  SolicitacaoAtivacao nova(String externalId, String customer, Instant criadaEm) {
    return SolicitacaoAtivacao.nova(
        UUID.randomUUID(),
        "sistema-a",
        externalId,
        customer,
        "INTERNET-500",
        "trace-" + externalId,
        criadaEm);
  }

  @Test
  void insereUmaVezPorExternalIdEPreservaMicrossegundos() {
    var primeira = nova("EXT-1", "CUST-1", t0);
    assertThat(solicitacaoAdapter.inserirSeAusente(primeira)).isTrue();
    assertThat(solicitacaoAdapter.inserirSeAusente(nova("EXT-1", "CUST-2", t0))).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from activation_request", Long.class))
        .isEqualTo(1);
    var lida = solicitacaoAdapter.porExternalId("EXT-1").orElseThrow();
    assertThat(lida.id()).isEqualTo(primeira.id());
    assertThat(lida.customerId()).isEqualTo("CUST-1");
    assertThat(lida.createdAt()).isEqualTo(t0);
    assertThat(solicitacaoAdapter.porId(primeira.id())).isPresent();
    assertThat(solicitacaoAdapter.porId(UUID.randomUUID())).isEmpty();
  }

  @Test
  void bancoRejeitaDuplicidadeMesmoForaDaAplicacao() {
    solicitacaoAdapter.inserirSeAusente(nova("EXT-1", "CUST-1", t0));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into activation_request (id, client_id, external_id, customer_id,"
                        + " product_code, status, next_attempt_at, created_at, updated_at) values"
                        + " (?, 'x', 'EXT-1', 'C', 'P', 'PENDING', now(), now(), now())",
                    UUID.randomUUID()))
        .hasMessageContaining("uk_activation_request_external_id");
  }

  @Test
  void atualizacaoComVersaoDesatualizadaFalha() {
    var original = nova("EXT-1", "CUST-1", t0);
    solicitacaoAdapter.inserirSeAusente(original);
    var leituraA = solicitacaoAdapter.porId(original.id()).orElseThrow();
    var leituraB = solicitacaoAdapter.porId(original.id()).orElseThrow();

    leituraA.iniciarProcessamento(t0.plusSeconds(1));
    solicitacaoAdapter.atualizar(leituraA);

    leituraB.iniciarProcessamento(t0.plusSeconds(2));
    assertThatThrownBy(() -> solicitacaoAdapter.atualizar(leituraB))
        .isInstanceOf(ConflitoDeVersaoException.class);

    var atual = solicitacaoAdapter.porId(original.id()).orElseThrow();
    assertThat(atual.status()).isEqualTo(PROCESSING);
    assertThat(atual.tentativas()).isEqualTo(1);
    assertThat(atual.versao()).isEqualTo(1);
    assertThat(atual.updatedAt()).isEqualTo(t0.plusSeconds(1));
  }

  @Test
  void listaPorFiltroDoMaisRecenteParaOMaisAntigo() {
    for (int i = 0; i < 5; i++)
      solicitacaoAdapter.inserirSeAusente(
          nova("EXT-" + i, i % 2 == 0 ? "CUST-A" : "CUST-B", t0.plus(i, ChronoUnit.MINUTES)));
    var processando = solicitacaoAdapter.porExternalId("EXT-4").orElseThrow();
    processando.iniciarProcessamento(t0.plusSeconds(600));
    solicitacaoAdapter.atualizar(processando);

    var clienteA = solicitacaoAdapter.listar(new Filtro("sistema-a", null, "CUST-A", 0, 2));
    assertThat(clienteA.total()).isEqualTo(3);
    assertThat(clienteA.itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-4", "EXT-2");
    assertThat(solicitacaoAdapter.listar(new Filtro("sistema-a", null, "CUST-A", 1, 2)).itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-0");

    var pendentesA = solicitacaoAdapter.listar(new Filtro("sistema-a", PENDING, "CUST-A", 0, 10));
    assertThat(pendentesA.itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-2", "EXT-0");
    assertThat(solicitacaoAdapter.listar(new Filtro("sistema-a", null, null, 0, 10)).total())
        .isEqualTo(5);
    assertThat(solicitacaoAdapter.listar(new Filtro("sistema-b", null, null, 0, 10)).total())
        .isZero();
  }

  @Test
  void instanciasConcorrentesNaoPegamAMesmaSolicitacao() throws Exception {
    for (int i = 0; i < 3; i++) solicitacaoAdapter.inserirSeAusente(nova("EXT-" + i, "C", t0));
    var primeiraReservou = new CountDownLatch(1);
    var liberarPrimeira = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<List<SolicitacaoAtivacao>> primeira =
          executor.submit(
              () ->
                  transacao.executar(
                      () -> {
                        var lote = solicitacaoAdapter.bloquearPendentes(t0.plusSeconds(1), 2);
                        primeiraReservou.countDown();
                        aguardar(liberarPrimeira);
                        return lote;
                      }));
      primeiraReservou.await(10, TimeUnit.SECONDS);
      // Com a primeira transação ainda aberta, a segunda pula as linhas travadas em vez de esperar.
      var segunda =
          executor
              .submit(
                  () ->
                      transacao.executar(
                          () -> solicitacaoAdapter.bloquearPendentes(t0.plusSeconds(1), 10)))
              .get(5, TimeUnit.SECONDS);
      liberarPrimeira.countDown();
      assertThat(primeira.get(10, TimeUnit.SECONDS)).hasSize(2);
      assertThat(segunda).hasSize(1);
      assertThat(segunda.getFirst().id())
          .isNotIn(primeira.get().stream().map(SolicitacaoAtivacao::id).toList());
    }
  }

  @Test
  void bloquearExigeTransacaoDoChamador() {
    assertThatThrownBy(() -> solicitacaoAdapter.bloquearPendentes(t0, 1))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void reconciliacaoDevolvePresaParaAFila() {
    var presa = nova("EXT-PRESA", "C", t0);
    solicitacaoAdapter.inserirSeAusente(presa);
    var lida = solicitacaoAdapter.porId(presa.id()).orElseThrow();
    lida.iniciarProcessamento(t0);
    solicitacaoAdapter.atualizar(lida);
    jdbc.update(
        "update activation_request set updated_at = now() - interval '10 minutes' where id = ?",
        presa.id());

    assertThat(reconciliar.reconciliar()).isEqualTo(1);
    var atual = solicitacaoAdapter.porId(presa.id()).orElseThrow();
    assertThat(atual.status()).isEqualTo(PENDING);
    assertThat(atual.ultimoErro()).contains("interrompido");
  }

  private static void aguardar(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
