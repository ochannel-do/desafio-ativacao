package com.exemplo.ativacao.infrastructure.persistence;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

public final class SolicitacaoRepositoryAdapter implements SolicitacaoRepository {
  private static final Sort MAIS_RECENTES =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
  private final SolicitacaoJpaRepository repository;

  public SolicitacaoRepositoryAdapter(SolicitacaoJpaRepository repository) {
    this.repository = repository;
  }

  public boolean inserirSeAusente(SolicitacaoAtivacao s) {
    return repository.inserirSeAusente(
            s.id(),
            s.externalId(),
            s.customerId(),
            s.productCode(),
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
    Specification<SolicitacaoEntity> spec = (root, query, cb) -> cb.conjunction();
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

  public void atualizar(SolicitacaoAtivacao s) {
    var entity = new SolicitacaoEntity();
    entity.id = s.id();
    entity.externalId = s.externalId();
    entity.customerId = s.customerId();
    entity.productCode = s.productCode();
    entity.status = s.status();
    entity.tentativas = s.tentativas();
    entity.createdAt = s.createdAt();
    entity.updatedAt = s.updatedAt();
    // A versão lida volta no merge; o Hibernate inclui "where version = ?" e falha se mudou.
    entity.version = s.versao();
    try {
      repository.saveAndFlush(entity);
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ConflitoDeVersaoException(s.id());
    }
  }

  static SolicitacaoAtivacao dominio(SolicitacaoEntity e) {
    return SolicitacaoAtivacao.reconstituir(
        e.id,
        e.externalId,
        e.customerId,
        e.productCode,
        e.status,
        e.tentativas,
        e.createdAt,
        e.updatedAt,
        e.version);
  }
}
