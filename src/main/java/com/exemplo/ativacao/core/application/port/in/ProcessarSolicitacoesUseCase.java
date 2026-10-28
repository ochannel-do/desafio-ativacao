package com.exemplo.ativacao.core.application.port.in;

public interface ProcessarSolicitacoesUseCase {
  /** Envia ao provedor um lote de solicitações vencidas e retorna quantas foram tentadas. */
  int processarLote();
}
