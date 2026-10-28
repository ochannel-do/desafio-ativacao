package com.exemplo.ativacao.core.application.usecase;

import com.exemplo.ativacao.core.application.port.in.ProcessarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.out.*;
import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao.*;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository.ConflitoDeVersaoException;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Clock;
import java.util.List;

/**
 * Três passos, nunca com transação aberta durante a chamada HTTP: (1) marca o lote como PROCESSING
 * e confirma; (2) chama o provedor; (3) grava o desfecho. Se a instância cair entre 1 e 3, a
 * reconciliação devolve a solicitação para a fila; a chave de idempotência evita ativar duas vezes.
 */
public final class ProcessarSolicitacoesService implements ProcessarSolicitacoesUseCase {
  private final SolicitacaoRepository solicitacaoAdapter;
  private final ProvedorAtivacao provedor;
  private final Transacao transacao;
  private final PoliticaDeRetentativa politica;
  private final Clock clock;
  private final int tamanhoLote;

  public ProcessarSolicitacoesService(
      SolicitacaoRepository solicitacaoAdapter,
      ProvedorAtivacao provedor,
      Transacao transacao,
      PoliticaDeRetentativa politica,
      Clock clock,
      int tamanhoLote) {
    this.solicitacaoAdapter = solicitacaoAdapter;
    this.provedor = provedor;
    this.transacao = transacao;
    this.politica = politica;
    this.clock = clock;
    this.tamanhoLote = tamanhoLote;
  }

  public int processarLote() {
    List<SolicitacaoAtivacao> lote = transacao.executar(this::reservarLote);
    for (var solicitacao : lote) {
      var resposta =
          provedor.ativar(
              new Pedido(
                  solicitacao.id(),
                  solicitacao.externalId(),
                  solicitacao.customerId(),
                  solicitacao.productCode(),
                  solicitacao.correlationId()));
      registrarDesfecho(solicitacao, resposta);
    }
    return lote.size();
  }

  private List<SolicitacaoAtivacao> reservarLote() {
    var agora = clock.instant();
    return solicitacaoAdapter.bloquearPendentes(agora, tamanhoLote).stream()
        .map(
            s -> {
              s.iniciarProcessamento(agora);
              return solicitacaoAdapter.atualizar(s);
            })
        .toList();
  }

  private void registrarDesfecho(SolicitacaoAtivacao solicitacao, Resposta resposta) {
    var agora = clock.instant();
    switch (resposta) {
      case Ativado a -> solicitacao.concluir(agora);
      case Rejeitado(var motivo) -> solicitacao.falhar(agora, motivo);
      case Indisponivel(var motivo, var aguardar) -> retentar(solicitacao, motivo, aguardar);
      case SemConfirmacao(var motivo) -> retentar(solicitacao, motivo, null);
    }
    try {
      transacao.executar(() -> solicitacaoAdapter.atualizar(solicitacao));
    } catch (ConflitoDeVersaoException e) {
      // A reconciliação já mexeu nesta solicitação; o estado dela prevalece e a próxima
      // tentativa repete com a mesma chave de idempotência.
    }
  }

  private void retentar(
      SolicitacaoAtivacao solicitacao, String motivo, java.time.Duration aguardar) {
    var agora = clock.instant();
    if (politica.esgotou(solicitacao.tentativas()))
      solicitacao.falhar(agora, "Tentativas esgotadas: " + motivo);
    else
      solicitacao.devolverParaFila(
          agora, politica.proximaTentativa(solicitacao.tentativas(), agora, aguardar), motivo);
  }
}
