package com.exemplo.ativacao.core.application.usecase;

import com.exemplo.ativacao.core.application.port.in.ReconciliarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.application.port.out.Transacao;
import java.time.Clock;
import java.time.Duration;

/**
 * PROCESSING além do prazo significa que a instância caiu ou perdeu a resposta. Não dá para saber
 * se o provedor ativou, então a solicitação volta para a fila e a chave de idempotência resolve.
 */
public final class ReconciliarSolicitacoesService implements ReconciliarSolicitacoesUseCase {
  static final String MOTIVO = "Processamento interrompido sem resposta do provedor";
  private final SolicitacaoRepository solicitacaoAdapter;
  private final Transacao transacao;
  private final PoliticaDeRetentativa politica;
  private final Clock clock;
  private final Duration prazo;
  private final int tamanhoLote;

  public ReconciliarSolicitacoesService(
      SolicitacaoRepository solicitacaoAdapter,
      Transacao transacao,
      PoliticaDeRetentativa politica,
      Clock clock,
      Duration prazo,
      int tamanhoLote) {
    this.solicitacaoAdapter = solicitacaoAdapter;
    this.transacao = transacao;
    this.politica = politica;
    this.clock = clock;
    this.prazo = prazo;
    this.tamanhoLote = tamanhoLote;
  }

  public int reconciliar() {
    return transacao.executar(
        () -> {
          var agora = clock.instant();
          var presas =
              solicitacaoAdapter.bloquearPresasEmProcessamento(agora.minus(prazo), tamanhoLote);
          for (var s : presas) {
            if (politica.esgotou(s.tentativas()))
              s.falhar(agora, "Tentativas esgotadas: " + MOTIVO);
            else s.devolverParaFila(agora, agora, MOTIVO);
            solicitacaoAdapter.atualizar(s);
          }
          return presas.size();
        });
  }
}
