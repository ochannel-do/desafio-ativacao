package com.exemplo.ativacao.infrastructure.persistence;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import com.exemplo.ativacao.core.application.port.out.Inbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class InboxJdbcAdapter implements Inbox {
  private static final String CONSUMIDOR = "ativacao-provedor-v1";
  private final JdbcTemplate jdbc;

  public InboxJdbcAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean reservar(Mensagem m, UUID posse, Instant agora) {
    int inseridas =
        jdbc.update(
            """
      insert into activation_inbox(consumer_name, event_id, request_id, client_id, request_version, lease_token, status, created_at)
      values (?, ?, ?, ?, ?, ?, 'PROCESSING', ?) on conflict(consumer_name, event_id) do nothing
      """,
            CONSUMIDOR,
            m.eventId(),
            m.requestId(),
            m.clientId(),
            m.requestVersion(),
            posse,
            Timestamp.from(agora));
    if (inseridas == 1) return true;
    var existente =
        jdbc.queryForMap(
            "select request_id, client_id, request_version, status from activation_inbox where consumer_name = ? and event_id = ? for update",
            CONSUMIDOR,
            m.eventId());
    if (!m.requestId().equals(existente.get("request_id"))
        || !m.clientId().equals(existente.get("client_id"))
        || m.requestVersion() != ((Number) existente.get("request_version")).longValue())
      throw new IllegalArgumentException("eventId reutilizado com outro conteúdo");
    if ("PROCESSING".equals(existente.get("status"))) throw new MensagemEmProcessamentoException();
    return false;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void concluir(Mensagem m, UUID posse, Instant agora) {
    if (jdbc.update(
            "update activation_inbox set status = 'COMPLETED', finished_at = ? where consumer_name = ? and event_id = ? and lease_token = ? and status = 'PROCESSING'",
            Timestamp.from(agora),
            CONSUMIDOR,
            m.eventId(),
            posse)
        != 1) throw new IllegalStateException("Reserva de inbox já encerrada");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recuperar(UUID requestId, long versaoEmProcessamento, Instant agora) {
    jdbc.update(
        "update activation_inbox set status = 'RECOVERED', finished_at = ? where consumer_name = ? and request_id = ? and request_version < ? and status = 'PROCESSING'",
        Timestamp.from(agora),
        CONSUMIDOR,
        requestId,
        versaoEmProcessamento);
  }
}
