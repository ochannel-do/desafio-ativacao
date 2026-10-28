package com.exemplo.ativacao.core.application.port.in;

public interface ReconciliarSolicitacoesUseCase {
  /** Recupera solicitações presas em PROCESSING e retorna quantas foram tratadas. */
  int reconciliar();
}
