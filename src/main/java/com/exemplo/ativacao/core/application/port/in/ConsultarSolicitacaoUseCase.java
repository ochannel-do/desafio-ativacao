package com.exemplo.ativacao.core.application.port.in;

import java.util.Optional;
import java.util.UUID;

public interface ConsultarSolicitacaoUseCase {
  Optional<SolicitacaoView> porId(UUID id);
}
