package com.exemplo.ativacao.core.domain.exception;

import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;

public final class TransicaoInvalidaException extends RuntimeException {
  public TransicaoInvalidaException(StatusSolicitacao atual, StatusSolicitacao destino) {
    super("Transição inválida: " + atual + " -> " + destino);
  }
}
