package com.exemplo.ativacao.infrastructure.transaction;

import com.exemplo.ativacao.core.application.port.out.Transacao;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransacaoSpring implements Transacao {
  private final TransactionTemplate template;

  public TransacaoSpring(TransactionTemplate template) {
    this.template = template;
  }

  public <T> T executar(Supplier<T> trabalho) {
    return template.execute(status -> trabalho.get());
  }
}
