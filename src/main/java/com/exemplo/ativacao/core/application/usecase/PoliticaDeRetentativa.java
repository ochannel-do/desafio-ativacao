package com.exemplo.ativacao.core.application.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/**
 * Backoff exponencial com jitter: espera {@code base * 2^(tentativa-1)}, limitada ao teto, sorteada
 * entre metade e o valor cheio para que instâncias e solicitações não voltem todas juntas.
 */
public record PoliticaDeRetentativa(
    int maximoTentativas, Duration base, Duration teto, DoubleSupplier aleatorio) {
  public PoliticaDeRetentativa {
    if (maximoTentativas < 1) throw new IllegalArgumentException("maximoTentativas < 1");
    if (base.isNegative() || base.isZero() || teto.compareTo(base) < 0)
      throw new IllegalArgumentException("base e teto inválidos");
  }

  public boolean esgotou(int tentativas) {
    return tentativas >= maximoTentativas;
  }

  /** Retry-After do provedor prevalece quando maior que o backoff, sempre limitado ao teto. */
  public Instant proximaTentativa(int tentativas, Instant agora, Duration retryAfter) {
    long fator = 1L << Math.min(Math.max(tentativas - 1, 0), 30);
    long cheio = Math.min(teto.toMillis(), base.toMillis() * fator);
    long espera = cheio / 2 + Math.round(aleatorio.getAsDouble() * (cheio - cheio / 2));
    if (retryAfter != null)
      espera = Math.max(espera, Math.min(retryAfter.toMillis(), teto.toMillis()));
    return agora.plusMillis(espera);
  }
}
