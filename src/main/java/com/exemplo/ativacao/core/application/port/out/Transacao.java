package com.exemplo.ativacao.core.application.port.out;

import java.util.function.Supplier;

/** Fronteira transacional explícita para casos de uso que alternam banco e chamada remota. */
public interface Transacao {
  <T> T executar(Supplier<T> trabalho);
}
