package com.exemplo.ativacao.core;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.out.Transacao;
import com.exemplo.ativacao.core.application.usecase.*;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.support.RepositorioEmMemoria;
import java.time.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReconciliarSolicitacoesServiceTest {
  final Instant agora = Instant.parse("2026-10-26T14:00:00Z");
  final RepositorioEmMemoria repository = new RepositorioEmMemoria();
  final Transacao transacao =
      new Transacao() {
        public <T> T executar(Supplier<T> trabalho) {
          return trabalho.get();
        }
      };
  final PoliticaDeRetentativa politica =
      new PoliticaDeRetentativa(2, Duration.ofSeconds(1), Duration.ofSeconds(10), () -> 0);
  final ReconciliarSolicitacoesService service =
      new ReconciliarSolicitacoesService(
          repository,
          transacao,
          politica,
          Clock.fixed(agora, ZoneOffset.UTC),
          Duration.ofSeconds(60),
          10);

  SolicitacaoAtivacao processandoDesde(Instant desde, int tentativas) {
    var s =
        SolicitacaoAtivacao.nova(
            UUID.randomUUID(), "sistema-a", "EXT-" + UUID.randomUUID(), "C", "P", null, desde);
    for (int i = 0; i < tentativas; i++) {
      if (i > 0) s.devolverParaFila(desde, desde, "x");
      s.iniciarProcessamento(desde);
    }
    return repository.salvar(s);
  }

  @Test
  void presaAlemDoPrazoVoltaParaFilaImediatamente() {
    var presa = processandoDesde(agora.minusSeconds(61), 1);
    var recente = processandoDesde(agora.minusSeconds(10), 1);
    assertThat(service.reconciliar()).isEqualTo(1);
    assertThat(repository.atual(presa.id()).status()).isEqualTo(PENDING);
    assertThat(repository.atual(presa.id()).proximaTentativaEm()).isEqualTo(agora);
    assertThat(repository.atual(recente.id()).status()).isEqualTo(PROCESSING);
  }

  @Test
  void presaSemTentativasRestantesFalha() {
    var presa = processandoDesde(agora.minusSeconds(120), 2);
    service.reconciliar();
    assertThat(repository.atual(presa.id()).status()).isEqualTo(FAILED);
  }
}
