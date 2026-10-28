package com.exemplo.ativacao.infrastructure.scheduling;

import com.exemplo.ativacao.core.application.port.in.ProcessarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.in.ReconciliarSolicitacoesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Cada instância roda o seu; FOR UPDATE SKIP LOCKED reparte o trabalho sem coordenação extra. */
public class AtivacaoAgendador {
  private static final Logger log = LoggerFactory.getLogger(AtivacaoAgendador.class);
  private final ProcessarSolicitacoesUseCase processar;
  private final ReconciliarSolicitacoesUseCase reconciliar;

  public AtivacaoAgendador(
      ProcessarSolicitacoesUseCase processar, ReconciliarSolicitacoesUseCase reconciliar) {
    this.processar = processar;
    this.reconciliar = reconciliar;
  }

  @Scheduled(fixedDelayString = "${ativacao.processamento.intervalo:1s}")
  public void processar() {
    try {
      // Esvazia o que já venceu sem esperar o próximo ciclo.
      while (processar.processarLote() > 0) {}
    } catch (RuntimeException e) {
      log.error("Falha no ciclo de processamento", e);
    }
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
