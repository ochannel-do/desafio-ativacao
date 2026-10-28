package com.exemplo.ativacao.infrastructure.transaction;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import org.springframework.transaction.support.TransactionTemplate;

public final class RegistrarSolicitacaoTransacional implements RegistrarSolicitacaoUseCase {
  private final RegistrarSolicitacaoUseCase delegate;
  private final TransactionTemplate transaction;

  public RegistrarSolicitacaoTransacional(
      RegistrarSolicitacaoUseCase delegate, TransactionTemplate transaction) {
    this.delegate = delegate;
    this.transaction = transaction;
  }

  public Resultado executar(Comando comando) {
    return transaction.execute(status -> delegate.executar(comando));
  }
}
