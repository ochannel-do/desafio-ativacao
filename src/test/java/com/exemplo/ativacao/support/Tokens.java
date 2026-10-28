package com.exemplo.ativacao.support;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Emite JWTs como o servidor de autorização faria, assinados com a chave HMAC do perfil test. */
public final class Tokens {
  public static final String CHAVE = "chave-de-teste-com-pelo-menos-32-bytes!!";
  public static final String EMISSOR = "https://auth.teste";
  public static final String AUDIENCIA = "ativacao-api";

  private Tokens() {}

  public static String para(String clientId, String... escopos) {
    return emitir(clientId, EMISSOR, AUDIENCIA, Instant.now().plusSeconds(300), escopos);
  }

  public static String emitir(
      String clientId, String emissor, String audiencia, Instant expira, String... escopos) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .subject(clientId)
              .claim("client_id", clientId)
              .issuer(emissor)
              .audience(List.of(audiencia))
              .claim("scope", String.join(" ", escopos))
              .issueTime(new Date())
              .expirationTime(Date.from(expira))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(CHAVE.getBytes()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
