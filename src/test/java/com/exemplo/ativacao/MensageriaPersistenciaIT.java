package com.exemplo.ativacao;

import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.Comando;
import com.exemplo.ativacao.core.application.port.out.Transacao;
import com.exemplo.ativacao.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class MensageriaPersistenciaIT {
  @Autowired RegistrarSolicitacaoUseCase registrar;
  @Autowired Transacao transacao;
  @Autowired JdbcTemplate jdbc;

  @Test
  void registroRepetidoPublicaUmaUnicaIntencaoDuravel() {
    var comando = new Comando("sistema-a", "OUTBOX-1", "C-1", "P-1", "correlation");
    registrar.executar(comando);
    registrar.executar(comando);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from activation_outbox where request_id = (select id from activation_request where external_id = 'OUTBOX-1')",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void rollbackDaSolicitacaoTambemDesfazOutbox() {
    assertThatThrownBy(
            () ->
                transacao.executar(
                    () -> {
                      registrar.executar(
                          new Comando("sistema-a", "OUTBOX-ROLLBACK", "C-1", "P-1", "correlation"));
                      throw new IllegalStateException("rollback esperado");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from activation_request where external_id = 'OUTBOX-ROLLBACK'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from activation_outbox o left join activation_request r on r.id = o.request_id where r.id is null",
                Long.class))
        .isZero();
  }
}
