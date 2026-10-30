package com.exemplo.ativacao.infrastructure.scheduling;

import com.exemplo.ativacao.core.application.port.in.ReconciliarSolicitacoesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Recupera tentativas interrompidas; o processamento normal é disparado pelo Kafka. */
public class AtivacaoAgendador {
  private static final Logger log = LoggerFactory.getLogger(AtivacaoAgendador.class);
  private final ReconciliarSolicitacoesUseCase reconciliar;

  public AtivacaoAgendador(ReconciliarSolicitacoesUseCase reconciliar) {
    this.reconciliar = reconciliar;
  }

  @Scheduled(fixedDelayString = "${ativacao.processamento.intervalo-reconciliacao:30s}")
  public void reconciliar() {
    try {
      int recuperadas = reconciliar.reconciliar();
      if (recuperadas > 0)
        log.atWarn()
            .addKeyValue("event", "stuck_requests_reconciled")
            .addKeyValue("count", recuperadas)
            .log("Solicitações presas em PROCESSING recuperadas");
    } catch (RuntimeException e) {
      log.error("Falha no ciclo de reconciliação", e);
    }
  }
}
