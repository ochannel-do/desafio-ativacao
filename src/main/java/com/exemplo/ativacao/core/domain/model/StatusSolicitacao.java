package com.exemplo.ativacao.core.domain.model;

public enum StatusSolicitacao {
  PENDING,
  PROCESSING,
  COMPLETED,
  FAILED;

  public boolean finalizado() {
    return this == COMPLETED || this == FAILED;
  }
}
