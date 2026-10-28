package com.exemplo.ativacao.core.application.port.in;

import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import java.time.Instant;
import java.util.UUID;

public record SolicitacaoView(
    UUID id,
    String externalId,
    String customerId,
    String productCode,
    StatusSolicitacao status,
    int tentativas,
    String ultimoErro,
    Instant createdAt,
    Instant updatedAt) {
  public static SolicitacaoView de(SolicitacaoAtivacao s) {
    return new SolicitacaoView(
        s.id(),
        s.externalId(),
        s.customerId(),
        s.productCode(),
        s.status(),
        s.tentativas(),
        s.ultimoErro(),
        s.createdAt(),
        s.updatedAt());
  }
}
