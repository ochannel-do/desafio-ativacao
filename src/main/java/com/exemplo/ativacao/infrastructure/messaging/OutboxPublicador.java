package com.exemplo.ativacao.infrastructure.messaging;

import com.exemplo.ativacao.core.application.port.out.Outbox;
import com.exemplo.ativacao.core.application.port.out.Transacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxPublicador {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublicador.class);
  private final Outbox outbox;
  private final Transacao transacao;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;
  private final MensageriaProperties props;
  private final Clock clock;
  private final MeterRegistry metricas;

  public OutboxPublicador(
      Outbox outbox,
      Transacao transacao,
      KafkaTemplate<String, String> kafka,
      ObjectMapper mapper,
      MensageriaProperties props,
      Clock clock,
      MeterRegistry metricas) {
    this.outbox = outbox;
    this.transacao = transacao;
    this.kafka = kafka;
    this.mapper = mapper;
    this.props = props;
    this.clock = clock;
    this.metricas = metricas;
  }

  @Scheduled(fixedDelayString = "${ativacao.mensageria.intervalo:1s}")
  public void publicar() {
    // Uma reserva por envio: o lease não é consumido aguardando os itens anteriores de um lote.
    for (int i = 0; i < 20; i++) {
      var agora = clock.instant();
      var lote =
          transacao.executar(() -> outbox.reservar(agora, agora.plus(props.prazoPublicacao()), 1));
      if (lote.isEmpty()) return;
      var publicacao = lote.getFirst();
      try {
        var m = publicacao.mensagem();
        kafka
            .send(props.topico(), m.requestId().toString(), mapper.writeValueAsString(m))
            .get(10, TimeUnit.SECONDS);
        transacao.executar(
            () -> {
              outbox.confirmar(publicacao, clock.instant());
              return null;
            });
        metricas.counter("ativacao.outbox.publicadas").increment();
      } catch (Exception e) {
        // Falha entre confirmação do broker e commit pode republicar o mesmo eventId.
        transacao.executar(
            () -> {
              outbox.liberar(publicacao, clock.instant().plus(props.intervaloFalha()));
              return null;
            });
        metricas.counter("ativacao.outbox.falhas").increment();
        log.atWarn()
            .addKeyValue("event", "outbox_publish_failed")
            .addKeyValue("eventId", publicacao.mensagem().eventId())
            .log("Publicação não confirmada; evento permanece na outbox");
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
