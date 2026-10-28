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
  private final SolicitacaoRepository solicitacaoAdapter;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public RegistrarSolicitacaoService(
      SolicitacaoRepository solicitacaoAdapter, Clock clock, Supplier<UUID> ids) {
    this.solicitacaoAdapter = solicitacaoAdapter;
    this.clock = clock;
    this.ids = ids;
  }

  public Resultado executar(Comando comando) {
    if (comando == null) throw new IllegalArgumentException("Comando obrigatório");
    var nova =
        SolicitacaoAtivacao.nova(
            ids.get(),
            comando.consumidor(),
            comando.externalId(),
            comando.customerId(),
            comando.productCode(),
            comando.correlationId(),
            clock.instant());
    if (solicitacaoAdapter.inserirSeAusente(nova)) return new Criada(SolicitacaoView.de(nova));
    var existente =
        solicitacaoAdapter
            .porExternalId(comando.externalId())
            .orElseThrow(() -> new IllegalStateException("externalId em conflito sem registro"));
    return existente.mesmoConteudo(
            comando.consumidor(), comando.customerId(), comando.productCode())
        ? new Repetida(SolicitacaoView.de(existente))
        : new Conflitante(comando.externalId());
  }
}
