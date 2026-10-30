package com.exemplo.ativacao.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
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
            (id, client_id, external_id, customer_id, product_code, correlation_id, status, attempts,
             next_attempt_at, created_at, updated_at, version)
          values (:id, :consumidor, :externalId, :customerId, :productCode, :correlationId, :status, 0,
                  :createdAt, :createdAt, :updatedAt, 0)
          on conflict (external_id) do nothing
          """,
      nativeQuery = true)
  int inserirSeAusente(
      @Param("id") UUID id,
      @Param("consumidor") String consumidor,
      @Param("externalId") String externalId,
      @Param("customerId") String customerId,
      @Param("productCode") String productCode,
      @Param("correlationId") String correlationId,
      @Param("status") String status,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt);

  @Query(
      value =
          "select * from activation_request where id = :id and client_id = :consumidor for update",
      nativeQuery = true)
  Optional<SolicitacaoEntity> bloquearPorId(
      @Param("id") UUID id, @Param("consumidor") String consumidor);

  Optional<SolicitacaoEntity> findByExternalId(String externalId);

  @Query(
      value =
          """
          select * from activation_request
          where status = 'PENDING' and next_attempt_at <= :agora
          order by next_attempt_at
          limit :limite
          for update skip locked
          """,
      nativeQuery = true)
  List<SolicitacaoEntity> bloquearPendentes(
      @Param("agora") Instant agora, @Param("limite") int limite);

  @Query(
      value =
          """
          select * from activation_request
          where status = 'PROCESSING' and updated_at < :limite
          order by updated_at
          limit :quantidade
          for update skip locked
          """,
      nativeQuery = true)
  List<SolicitacaoEntity> bloquearPresasEmProcessamento(
      @Param("limite") Instant limite, @Param("quantidade") int quantidade);
}
