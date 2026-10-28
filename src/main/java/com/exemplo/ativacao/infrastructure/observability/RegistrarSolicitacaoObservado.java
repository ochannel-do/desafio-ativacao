package com.exemplo.ativacao.infrastructure.observability;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import io.micrometer.observation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistrarSolicitacaoObservado implements RegistrarSolicitacaoUseCase {
  private static final Logger log = LoggerFactory.getLogger(RegistrarSolicitacaoObservado.class);
  private final RegistrarSolicitacaoUseCase delegate;
  private final ObservationRegistry registry;

  public RegistrarSolicitacaoObservado(
      RegistrarSolicitacaoUseCase delegate, ObservationRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  public Resultado executar(Comando comando) {
    var observation = Observation.createNotStarted("ativacao.registrar", registry);
    return observation.observe(
        () -> {
          var resultado = delegate.executar(comando);
          String desfecho =
              switch (resultado) {
                case Criada c -> "criada";
                case Repetida r -> "repetida";
                case Conflitante c -> "conflitante";
              };
          observation.lowCardinalityKeyValue("desfecho", desfecho);
          log.atInfo()
              .addKeyValue("event", "activation_request_registered")
              .addKeyValue("outcome", desfecho)
              .addKeyValue("externalId", comando.externalId())
              .log("Solicitação de ativação registrada");
          return resultado;
        });
  }
}
