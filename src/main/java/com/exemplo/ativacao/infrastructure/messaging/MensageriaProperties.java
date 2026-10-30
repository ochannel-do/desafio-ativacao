package com.exemplo.ativacao.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ativacao.mensageria")
public record MensageriaProperties(
    @DefaultValue("true") boolean habilitado,
    @DefaultValue("ativacao.local.solicitacoes.v1") String topico,
    @DefaultValue("ativacao-provedor-v1") String grupo,
    @DefaultValue("3") int particoes,
    @DefaultValue("1") short replicas,
    @DefaultValue("30s") Duration prazoPublicacao,
    @DefaultValue("5s") Duration intervaloFalha) {
  public MensageriaProperties {
    if (topico == null
        || !topico.matches("[a-zA-Z0-9._-]{1,200}")
        || grupo == null
        || grupo.isBlank()
        || particoes < 1
        || replicas < 1
        || prazoPublicacao.compareTo(Duration.ofSeconds(10)) <= 0
        || intervaloFalha.isNegative()
        || intervaloFalha.isZero())
      throw new IllegalArgumentException("Configuração de mensageria inválida");
  }
}
