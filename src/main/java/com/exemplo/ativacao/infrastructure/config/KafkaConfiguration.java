package com.exemplo.ativacao.infrastructure.config;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase;
import com.exemplo.ativacao.core.application.port.out.*;
import com.exemplo.ativacao.infrastructure.messaging.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(MensageriaProperties.class)
@ConditionalOnProperty(
    name = "ativacao.mensageria.habilitado",
    havingValue = "true",
    matchIfMissing = true)
public class KafkaConfiguration {
  @Bean
  NewTopic solicitacoes(MensageriaProperties p) {
    return TopicBuilder.name(p.topico()).partitions(p.particoes()).replicas(p.replicas()).build();
  }

  @Bean
  NewTopic mensagensInvalidas(MensageriaProperties p) {
    return TopicBuilder.name(p.topico() + ".dlt")
        .partitions(p.particoes())
        .replicas(p.replicas())
        .build();
  }

  @Bean
  AtivacaoKafkaListener ativacaoKafkaListener(
      ProcessarMensagemUseCase processar, ObjectMapper mapper) {
    return new AtivacaoKafkaListener(processar, mapper);
  }

  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> template, MensageriaProperties props, MeterRegistry metricas) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template, (r, e) -> new TopicPartition(props.topico() + ".dlt", -1));
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(15));
    var handler =
        new DefaultErrorHandler(
            (r, e) -> {
              recoverer.accept(r, e);
              metricas.counter("ativacao.kafka.dlt").increment();
            },
            new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
    // Falha de banco e reserva em andamento não descartam trabalho. Só envelopes inválidos vão à
    // DLT.
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }

  @Bean
  @ConditionalOnProperty(
      name = "ativacao.processamento.habilitado",
      havingValue = "true",
      matchIfMissing = true)
  OutboxPublicador outboxPublicador(
      Outbox outbox,
      Transacao transacao,
      KafkaTemplate<String, String> kafka,
      ObjectMapper mapper,
      MensageriaProperties props,
      Clock clock,
      MeterRegistry metricas) {
    return new OutboxPublicador(outbox, transacao, kafka, mapper, props, clock, metricas);
  }
}
