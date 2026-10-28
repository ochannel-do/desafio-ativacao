package com.exemplo.ativacao.core;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.domain.exception.TransicaoInvalidaException;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SolicitacaoAtivacaoTest {
  static final Instant T0 = Instant.parse("2026-10-26T14:03:11Z");
  static final Instant T1 = T0.plusSeconds(1);

  static SolicitacaoAtivacao nova() {
    return SolicitacaoAtivacao.nova(
        UUID.randomUUID(), "EXT-12345", "CUST-10001", "INTERNET-500", T0);
  }

  @Test
  void nasceComoPendenteSemTentativas() {
    var s = nova();
    assertThat(s.status()).isEqualTo(PENDING);
    assertThat(s.tentativas()).isZero();
    assertThat(s.createdAt()).isEqualTo(T0).isEqualTo(s.updatedAt());
  }

  @Test
  void caminhoFelizContaTentativaEAtualizaData() {
    var s = nova();
    s.iniciarProcessamento(T1);
    assertThat(s.status()).isEqualTo(PROCESSING);
    assertThat(s.tentativas()).isEqualTo(1);
    s.concluir(T1.plusSeconds(1));
    assertThat(s.status()).isEqualTo(COMPLETED);
    assertThat(s.updatedAt()).isEqualTo(T1.plusSeconds(1));
    assertThat(s.createdAt()).isEqualTo(T0);
  }

  @Test
  void falhaTemporariaVoltaParaFilaEPreservaContador() {
    var s = nova();
    s.iniciarProcessamento(T1);
    s.devolverParaFila(T1);
    assertThat(s.status()).isEqualTo(PENDING);
    s.iniciarProcessamento(T1);
    assertThat(s.tentativas()).isEqualTo(2);
    s.falhar(T1);
    assertThat(s.status()).isEqualTo(FAILED);
  }

  @Test
  void estadosFinaisNaoVoltam() {
    var concluida = nova();
    concluida.iniciarProcessamento(T1);
    concluida.concluir(T1);
    assertThatThrownBy(() -> concluida.iniciarProcessamento(T1))
        .isInstanceOf(TransicaoInvalidaException.class)
        .hasMessageContaining("COMPLETED -> PROCESSING");
    assertThatThrownBy(() -> concluida.falhar(T1)).isInstanceOf(TransicaoInvalidaException.class);
    assertThatThrownBy(() -> concluida.devolverParaFila(T1))
        .isInstanceOf(TransicaoInvalidaException.class);
    assertThat(concluida.status().finalizado()).isTrue();
  }

  @Test
  void naoConcluiSemEstarProcessando() {
    var s = nova();
    assertThatThrownBy(() -> s.concluir(T1)).isInstanceOf(TransicaoInvalidaException.class);
    assertThat(s.status()).isEqualTo(PENDING);
    assertThat(s.updatedAt()).isEqualTo(T0);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "-EXT", "EXT 1", "EXT/1"})
  void rejeitaIdentificadorInvalido(String valor) {
    assertThatThrownBy(() -> SolicitacaoAtivacao.nova(UUID.randomUUID(), valor, "C", "P", T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("externalId");
  }

  @Test
  void rejeitaIdentificadorLongo() {
    String longo = "A".repeat(SolicitacaoAtivacao.TAMANHO_MAXIMO + 1);
    assertThatThrownBy(() -> SolicitacaoAtivacao.nova(UUID.randomUUID(), "E", "C", longo, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("productCode");
  }

  @Test
  void comparaConteudoParaRetry() {
    var s = nova();
    assertThat(s.mesmoConteudo("CUST-10001", "INTERNET-500")).isTrue();
    assertThat(s.mesmoConteudo("CUST-10001", "INTERNET-900")).isFalse();
    assertThat(s.mesmoConteudo("CUST-2", "INTERNET-500")).isFalse();
  }
}
