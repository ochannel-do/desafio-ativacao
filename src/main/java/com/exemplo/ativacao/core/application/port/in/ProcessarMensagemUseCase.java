package com.exemplo.ativacao.core.application.port.in;

import java.util.Objects;
import java.util.UUID;

public interface ProcessarMensagemUseCase {
  void executar(Mensagem mensagem);

  record Mensagem(
      int schemaVersion,
      UUID eventId,
      UUID requestId,
      String clientId,
      long requestVersion,
      String correlationId) {
    public Mensagem {
      if (schemaVersion != 1) throw new IllegalArgumentException("Versão de evento não suportada");
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(requestId, "requestId");
      if (clientId == null || clientId.isBlank() || clientId.length() > 128 || requestVersion < 0)
        throw new IllegalArgumentException("Identificação do evento inválida");
    }
  }
}
