package com.exemplo.ativacao.support;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import java.time.Instant;
import java.util.*;

/** Fake com a mesma semântica de versão do adaptador JPA, para testes de caso de uso sem banco. */
public class RepositorioEmMemoria implements SolicitacaoRepository {
  private final Map<UUID, SolicitacaoAtivacao.Dados> linhas = new LinkedHashMap<>();

  public SolicitacaoAtivacao salvar(SolicitacaoAtivacao s) {
    linhas.put(s.id(), s.dados());
    return s;
  }

  public SolicitacaoAtivacao atual(UUID id) {
    return SolicitacaoAtivacao.reconstituir(linhas.get(id));
  }

  public boolean inserirSeAusente(SolicitacaoAtivacao s) {
    if (linhas.values().stream().anyMatch(d -> d.externalId().equals(s.externalId()))) return false;
    linhas.put(s.id(), s.dados());
    return true;
  }

  public Optional<SolicitacaoAtivacao> porId(UUID id) {
    return Optional.ofNullable(linhas.get(id)).map(SolicitacaoAtivacao::reconstituir);
  }

  public Optional<SolicitacaoAtivacao> bloquearPorId(UUID id, String consumidor) {
    return porId(id).filter(s -> s.pertenceA(consumidor));
  }

  public Optional<SolicitacaoAtivacao> porExternalId(String externalId) {
    return linhas.values().stream()
        .filter(d -> d.externalId().equals(externalId))
        .findFirst()
        .map(SolicitacaoAtivacao::reconstituir);
  }

  public Fatia listar(Filtro filtro) {
    var itens =
        linhas.values().stream()
            .filter(d -> d.consumidor().equals(filtro.consumidor()))
            .map(SolicitacaoAtivacao::reconstituir)
            .toList();
    return new Fatia(itens, itens.size());
  }

  public List<SolicitacaoAtivacao> bloquearPendentes(Instant agora, int limite) {
    return linhas.values().stream()
        .filter(d -> d.status() == StatusSolicitacao.PENDING)
        .filter(d -> !d.proximaTentativaEm().isAfter(agora))
        .limit(limite)
        .map(SolicitacaoAtivacao::reconstituir)
        .toList();
  }

  public List<SolicitacaoAtivacao> bloquearPresasEmProcessamento(Instant limite, int quantidade) {
    return linhas.values().stream()
        .filter(d -> d.status() == StatusSolicitacao.PROCESSING)
        .filter(d -> d.updatedAt().isBefore(limite))
        .limit(quantidade)
        .map(SolicitacaoAtivacao::reconstituir)
        .toList();
  }

  public SolicitacaoAtivacao atualizar(SolicitacaoAtivacao s) {
    var gravada = linhas.get(s.id());
    if (gravada == null || gravada.versao() != s.versao())
      throw new ConflitoDeVersaoException(s.id());
    var d = s.dados();
    var nova =
        new SolicitacaoAtivacao.Dados(
            d.id(),
            d.consumidor(),
            d.externalId(),
            d.customerId(),
            d.productCode(),
            d.correlationId(),
            d.status(),
            d.tentativas(),
            d.proximaTentativaEm(),
            d.ultimoErro(),
            d.createdAt(),
            d.updatedAt(),
            d.versao() + 1);
    linhas.put(s.id(), nova);
    return SolicitacaoAtivacao.reconstituir(nova);
  }
}
