package com.exemplo.ativacao.core;

import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.usecase.PoliticaDeRetentativa;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PoliticaDeRetentativaTest {
  final Instant agora = Instant.parse("2026-10-26T14:00:00Z");

  PoliticaDeRetentativa politica(double sorteio) {
    return new PoliticaDeRetentativa(
        5, Duration.ofSeconds(2), Duration.ofMinutes(1), () -> sorteio);
  }

  @Test
  void dobraACadaTentativaComJitterEntreMetadeEValorCheio() {
    assertThat(politica(1.0).proximaTentativa(1, agora, null)).isEqualTo(agora.plusSeconds(2));
    assertThat(politica(1.0).proximaTentativa(3, agora, null)).isEqualTo(agora.plusSeconds(8));
    assertThat(politica(0.0).proximaTentativa(3, agora, null)).isEqualTo(agora.plusSeconds(4));
  }

  @Test
  void respeitaOTeto() {
    assertThat(politica(1.0).proximaTentativa(30, agora, null)).isEqualTo(agora.plusSeconds(60));
    assertThat(politica(1.0).proximaTentativa(500, agora, null)).isEqualTo(agora.plusSeconds(60));
  }

  @Test
  void retryAfterMaiorPrevaleceMasNaoPassaDoTeto() {
    assertThat(politica(0.0).proximaTentativa(1, agora, Duration.ofSeconds(30)))
        .isEqualTo(agora.plusSeconds(30));
    assertThat(politica(1.0).proximaTentativa(4, agora, Duration.ofSeconds(1)))
        .isEqualTo(agora.plusSeconds(16));
    assertThat(politica(0.0).proximaTentativa(1, agora, Duration.ofHours(1)))
        .isEqualTo(agora.plusSeconds(60));
  }

  @Test
  void esgotaNoMaximo() {
    assertThat(politica(0).esgotou(4)).isFalse();
    assertThat(politica(0).esgotou(5)).isTrue();
  }

  @Test
  void rejeitaConfiguracaoInvalida() {
    assertThatThrownBy(
            () ->
                new PoliticaDeRetentativa(0, Duration.ofSeconds(1), Duration.ofSeconds(1), () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PoliticaDeRetentativa(1, Duration.ofSeconds(5), Duration.ofSeconds(1), () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
