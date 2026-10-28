package com.exemplo.ativacao.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Em produção, {@code jwkSetUri} aponta para as chaves públicas do servidor de autorização (RS256).
 * {@code chaveHmac} existe só para desenvolvimento local e testes, sem servidor de autorização.
 */
@ConfigurationProperties("ativacao.seguranca")
public record SegurancaProperties(
    String emissor, String audiencia, String jwkSetUri, String chaveHmac) {
  public SegurancaProperties {
    if (emissor == null || emissor.isBlank())
      throw new IllegalArgumentException("ativacao.seguranca.emissor obrigatório");
    if (audiencia == null || audiencia.isBlank())
      throw new IllegalArgumentException("ativacao.seguranca.audiencia obrigatória");
    boolean semJwks = jwkSetUri == null || jwkSetUri.isBlank();
    boolean semChave = chaveHmac == null || chaveHmac.isBlank();
    if (semJwks == semChave)
      throw new IllegalArgumentException(
          "Configure exatamente um entre ativacao.seguranca.jwk-set-uri e chave-hmac");
    if (!semChave && chaveHmac.length() < 32)
      throw new IllegalArgumentException("chave-hmac precisa de pelo menos 32 caracteres");
  }

  @Override
  public String toString() {
    return "SegurancaProperties[emissor=" + emissor + ", audiencia=" + audiencia + "]";
  }
}
