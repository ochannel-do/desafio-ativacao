package com.exemplo.ativacao.core.application.port.in;

import java.util.Optional;
import java.util.UUID;

public interface ConsultarSolicitacaoUseCase {
  /** Vazio também quando a solicitação é de outro consumidor: não revela que ela existe. */
  Optional<SolicitacaoView> porId(UUID id, String consumidor);
}
