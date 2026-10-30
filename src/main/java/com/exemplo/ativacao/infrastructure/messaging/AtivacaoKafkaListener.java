package com.exemplo.ativacao.infrastructure.messaging;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase;
import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

public class AtivacaoKafkaListener {
  private final ProcessarMensagemUseCase processar;
  private final ObjectMapper mapper;

  public AtivacaoKafkaListener(ProcessarMensagemUseCase processar, ObjectMapper mapper) {
    this.processar = processar;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${ativacao.mensageria.topico}", groupId = "${ativacao.mensageria.grupo}")
  public void receber(ConsumerRecord<String, String> record) {
    Mensagem m;
    try {
      m = mapper.readValue(record.value(), Mensagem.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Envelope de ativação inválido", e);
    }
    if (m == null || !m.requestId().toString().equals(record.key()))
      throw new IllegalArgumentException("Chave Kafka diferente da solicitação");
    processar.executar(m);
    // AckMode.RECORD e auto-commit desativado: offset só avança após o retorno do caso de uso.
  }
}
