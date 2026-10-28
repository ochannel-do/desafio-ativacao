package com.exemplo.ativacao.infrastructure.web.dto;

import com.exemplo.ativacao.core.application.port.in.SolicitacaoView;
import java.time.Instant;
import java.util.UUID;

public record SolicitacaoResponse(
    UUID id,
    String externalId,
    String customerId,
    String productCode,
    String status,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant updatedAt) {
  public static SolicitacaoResponse de(SolicitacaoView v) {
    return new SolicitacaoResponse(
        v.id(),
        v.externalId(),
        v.customerId(),
        v.productCode(),
        v.status().name(),
        v.tentativas(),
        v.ultimoErro(),
        v.createdAt(),
        v.updatedAt());
  }
}
