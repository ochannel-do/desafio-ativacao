package com.exemplo.ativacao.infrastructure.web;

import org.springframework.security.oauth2.jwt.Jwt;

/** Identidade do sistema cliente: client_id do fluxo client credentials, ou sub como fallback. */
final class Consumidor {
  private Consumidor() {}

  static String de(Jwt jwt) {
    String clientId = jwt.getClaimAsString("client_id");
    if (clientId != null && !clientId.isBlank()) return clientId;
    String azp = jwt.getClaimAsString("azp");
    if (azp != null && !azp.isBlank()) return azp;
    if (jwt.getSubject() == null || jwt.getSubject().isBlank())
      throw new IllegalStateException("Token sem identificação do consumidor");
    return jwt.getSubject();
  }
}
