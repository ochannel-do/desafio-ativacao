package com.exemplo.ativacao.core.application.usecase;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import com.exemplo.ativacao.core.application.port.in.SolicitacaoView;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A unicidade vem da constraint do banco, não de uma consulta prévia: "existe? então insere" deixa
 * uma janela em que duas requisições simultâneas passam pela consulta e as duas inserem.
 */
public final class RegistrarSolicitacaoService implements RegistrarSolicitacaoUseCase {
  private final SolicitacaoRepository repository;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public RegistrarSolicitacaoService(
      SolicitacaoRepository repository, Clock clock, Supplier<UUID> ids) {
    this.repository = repository;
    this.clock = clock;
    this.ids = ids;
  }

  public Resultado executar(Comando comando) {
    if (comando == null) throw new IllegalArgumentException("Comando obrigatório");
    var nova =
        SolicitacaoAtivacao.nova(
            ids.get(),
            comando.externalId(),
            comando.customerId(),
            comando.productCode(),
            comando.correlationId(),
            clock.instant());
    if (repository.inserirSeAusente(nova)) return new Criada(SolicitacaoView.de(nova));
    var existente =
        repository
            .porExternalId(comando.externalId())
            .orElseThrow(() -> new IllegalStateException("externalId em conflito sem registro"));
    return existente.mesmoConteudo(comando.customerId(), comando.productCode())
        ? new Repetida(SolicitacaoView.de(existente))
        : new Conflitante(comando.externalId());
  }
}
