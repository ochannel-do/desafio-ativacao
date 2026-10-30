package com.exemplo.ativacao.infrastructure.persistence;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import com.exemplo.ativacao.core.application.port.out.Outbox;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxJdbcAdapter implements Outbox {
  private final JdbcTemplate jdbc;

  public OutboxJdbcAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void agendar(SolicitacaoAtivacao s) {
    jdbc.update(
        """
      insert into activation_outbox(event_id, request_id, client_id, request_version, correlation_id, available_at)
      values (?, ?, ?, ?, ?, ?) on conflict(request_id, request_version) do nothing
      """,
        UUID.randomUUID(),
        s.id(),
        s.consumidor(),
        s.versao(),
        s.correlationId(),
        Timestamp.from(s.proximaTentativaEm()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<Publicacao> reservar(Instant agora, Instant expiraEm, int limite) {
    var posse = UUID.randomUUID();
    return jdbc.query(
        """
      update activation_outbox set lease_token = ?, lease_until = ?
      where event_id in (
        select event_id from activation_outbox
        where published_at is null and available_at <= ? and (lease_until is null or lease_until <= ?)
        order by available_at, event_id limit ? for update skip locked
      ) returning event_id, request_id, client_id, request_version, correlation_id
      """,
        (rs, row) ->
            new Publicacao(
                new Mensagem(
                    1,
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("request_id", UUID.class),
                    rs.getString("client_id"),
                    rs.getLong("request_version"),
                    rs.getString("correlation_id")),
                posse),
        posse,
        Timestamp.from(expiraEm),
        Timestamp.from(agora),
        Timestamp.from(agora),
        limite);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void confirmar(Publicacao p, Instant agora) {
    jdbc.update(
        "update activation_outbox set published_at = ?, lease_token = null, lease_until = null where event_id = ? and lease_token = ? and published_at is null",
        Timestamp.from(agora),
        p.mensagem().eventId(),
        p.posse());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void liberar(Publicacao p, Instant tentarEm) {
    jdbc.update(
        "update activation_outbox set available_at = ?, lease_token = null, lease_until = null where event_id = ? and lease_token = ? and published_at is null",
        Timestamp.from(tentarEm),
        p.mensagem().eventId(),
        p.posse());
  }
}
