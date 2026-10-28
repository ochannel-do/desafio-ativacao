package com.exemplo.ativacao.infrastructure.persistence;

import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activation_request")
public class SolicitacaoEntity {
  @Id UUID id;

  @Column(name = "external_id", nullable = false, updatable = false)
  String externalId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  String customerId;

  @Column(name = "product_code", nullable = false, updatable = false)
  String productCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  StatusSolicitacao status;

  @Column(name = "attempts", nullable = false)
  int tentativas;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version Long version;

  protected SolicitacaoEntity() {}
}
