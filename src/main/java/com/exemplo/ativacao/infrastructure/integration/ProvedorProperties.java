package com.exemplo.ativacao.infrastructure.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code apiKey} vem de variável de ambiente ou cofre de segredos, nunca do repositório. */
@ConfigurationProperties("ativacao.provedor")
public record ProvedorProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout, String apiKey) {
  public ProvedorProperties {
    if (baseUrl == null || baseUrl.isBlank())
      throw new IllegalArgumentException("ativacao.provedor.base-url obrigatório");
    if (connectTimeout == null) connectTimeout = Duration.ofSeconds(1);
    if (readTimeout == null) readTimeout = Duration.ofSeconds(3);
  }

  @Override
  public String toString() {
    return "ProvedorProperties[baseUrl=" + baseUrl + ", apiKey=***]";
  }
}
