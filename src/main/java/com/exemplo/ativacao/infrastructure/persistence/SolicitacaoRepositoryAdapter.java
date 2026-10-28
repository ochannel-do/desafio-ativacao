package com.exemplo.ativacao.infrastructure.persistence;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class SolicitacaoRepositoryAdapter implements SolicitacaoRepository {
  private static final Sort MAIS_RECENTES =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
  private final SolicitacaoJpaRepository repository;

  public SolicitacaoRepositoryAdapter(SolicitacaoJpaRepository repository) {
    this.repository = repository;
  }

  public boolean inserirSeAusente(SolicitacaoAtivacao s) {
    return repository.inserirSeAusente(
            s.id(),
            s.consumidor(),
            s.externalId(),
            s.customerId(),
            s.productCode(),
            s.correlationId(),
            s.status().name(),
            s.createdAt(),
            s.updatedAt())
        == 1;
  }

  public Optional<SolicitacaoAtivacao> porId(UUID id) {
    return repository.findById(id).map(SolicitacaoRepositoryAdapter::dominio);
  }

  public Optional<SolicitacaoAtivacao> porExternalId(String externalId) {
    return repository.findByExternalId(externalId).map(SolicitacaoRepositoryAdapter::dominio);
  }

  public Fatia listar(Filtro filtro) {
    Specification<SolicitacaoEntity> spec =
        (root, query, cb) -> cb.equal(root.get("consumidor"), filtro.consumidor());
    if (filtro.status() != null)
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filtro.status()));
    if (filtro.customerId() != null)
      spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), filtro.customerId()));
    var page =
        repository.findAll(spec, PageRequest.of(filtro.pagina(), filtro.tamanho(), MAIS_RECENTES));
    return new Fatia(
        page.getContent().stream().map(SolicitacaoRepositoryAdapter::dominio).toList(),
        page.getTotalElements());
  }

  // O lock de FOR UPDATE só dura até o commit; sem a transação do chamador ele seria inútil.
  @Transactional(propagation = Propagation.MANDATORY)
  public List<SolicitacaoAtivacao> bloquearPendentes(Instant agora, int limite) {
    return repository.bloquearPendentes(agora, limite).stream()
        .map(SolicitacaoRepositoryAdapter::dominio)
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<SolicitacaoAtivacao> bloquearPresasEmProcessamento(Instant limite, int quantidade) {
    return repository.bloquearPresasEmProcessamento(limite, quantidade).stream()
        .map(SolicitacaoRepositoryAdapter::dominio)
        .toList();
  }

  public SolicitacaoAtivacao atualizar(SolicitacaoAtivacao s) {
    var d = s.dados();
    var entity = new SolicitacaoEntity();
    entity.id = d.id();
    entity.consumidor = d.consumidor();
    entity.externalId = d.externalId();
    entity.customerId = d.customerId();
    entity.productCode = d.productCode();
    entity.correlationId = d.correlationId();
    entity.status = d.status();
    entity.tentativas = d.tentativas();
    entity.proximaTentativaEm = d.proximaTentativaEm();
    entity.ultimoErro = d.ultimoErro();
    entity.createdAt = d.createdAt();
    entity.updatedAt = d.updatedAt();
    // A versão lida volta no merge; o Hibernate inclui "where version = ?" e falha se mudou.
    entity.version = d.versao();
    try {
      return dominio(repository.saveAndFlush(entity));
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ConflitoDeVersaoException(s.id());
    }
  }

  static SolicitacaoAtivacao dominio(SolicitacaoEntity e) {
    return SolicitacaoAtivacao.reconstituir(
        new SolicitacaoAtivacao.Dados(
            e.id,
            e.consumidor,
            e.externalId,
            e.customerId,
            e.productCode,
            e.correlationId,
            e.status,
            e.tentativas,
            e.proximaTentativaEm,
            e.ultimoErro,
            e.createdAt,
            e.updatedAt,
            e.version));
  }
}
