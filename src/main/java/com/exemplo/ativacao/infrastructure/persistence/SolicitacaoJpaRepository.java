package com.exemplo.ativacao.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SolicitacaoJpaRepository
    extends JpaRepository<SolicitacaoEntity, UUID>, JpaSpecificationExecutor<SolicitacaoEntity> {
  /**
   * {@code ON CONFLICT DO NOTHING} espera a transação concorrente que inseriu o mesmo externalId e
   * devolve 0 linhas, sem abortar a transação atual como faria uma violação de constraint.
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          insert into activation_request
            (id, external_id, customer_id, product_code, status, attempts,
             created_at, updated_at, version)
          values (:id, :externalId, :customerId, :productCode, :status, 0,
                  :createdAt, :updatedAt, 0)
          on conflict (external_id) do nothing
          """,
      nativeQuery = true)
  int inserirSeAusente(
      @Param("id") UUID id,
      @Param("externalId") String externalId,
      @Param("customerId") String customerId,
      @Param("productCode") String productCode,
      @Param("status") String status,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt);

  Optional<SolicitacaoEntity> findByExternalId(String externalId);
}
