package com.exemplo.ativacao.core;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao;
import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao.*;
import com.exemplo.ativacao.core.application.port.out.Transacao;
import com.exemplo.ativacao.core.application.usecase.*;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.support.RepositorioEmMemoria;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProcessarSolicitacoesServiceTest {
  final Instant agora = Instant.parse("2026-10-26T14:00:00Z");
  final Clock clock = Clock.fixed(agora, ZoneOffset.UTC);
  final RepositorioEmMemoria solicitacaoAdapter = new RepositorioEmMemoria();
  final List<Pedido> pedidos = new ArrayList<>();
  final Deque<Resposta> respostas = new ArrayDeque<>();
  boolean emTransacao;

  final Transacao transacao =
      new Transacao() {
        public <T> T executar(Supplier<T> trabalho) {
          emTransacao = true;
          try {
            return trabalho.get();
          } finally {
            emTransacao = false;
          }
        }
      };

  final ProvedorAtivacao provedor =
      pedido -> {
        assertThat(emTransacao).as("chamada HTTP fora de transação").isFalse();
        pedidos.add(pedido);
        return respostas.removeFirst();
      };

  final PoliticaDeRetentativa politica =
      new PoliticaDeRetentativa(3, Duration.ofSeconds(2), Duration.ofMinutes(1), () -> 1.0);
  final ProcessarSolicitacoesService service =
      new ProcessarSolicitacoesService(
          solicitacaoAdapter, provedor, transacao, politica, clock, 10);

  SolicitacaoAtivacao pendente() {
    return solicitacaoAdapter.salvar(
        SolicitacaoAtivacao.nova(
            UUID.randomUUID(), "sistema-a", "EXT-1", "CUST-1", "INTERNET-500", "trace-1", agora));
  }

  @Test
  void sucessoConcluiEEnviaChaveDeIdempotenciaECorrelacao() {
    var s = pendente();
    respostas.add(new Ativado());
    assertThat(service.processarLote()).isEqualTo(1);
    var atual = solicitacaoAdapter.atual(s.id());
    assertThat(atual.status()).isEqualTo(COMPLETED);
    assertThat(atual.tentativas()).isEqualTo(1);
    assertThat(pedidos.getFirst().chaveIdempotencia()).isEqualTo(s.id());
    assertThat(pedidos.getFirst().correlationId()).isEqualTo("trace-1");
  }

  @Test
  void recusaDefinitivaFalhaSemNovaTentativa() {
    var s = pendente();
    respostas.add(new Rejeitado("HTTP 400"));
    service.processarLote();
    var atual = solicitacaoAdapter.atual(s.id());
    assertThat(atual.status()).isEqualTo(FAILED);
    assertThat(atual.ultimoErro()).isEqualTo("HTTP 400");
  }

  @Test
  void falhaTemporariaVoltaParaFilaComBackoff() {
    var s = pendente();
    respostas.add(new Indisponivel("HTTP 500", null));
    service.processarLote();
    var atual = solicitacaoAdapter.atual(s.id());
    assertThat(atual.status()).isEqualTo(PENDING);
    assertThat(atual.proximaTentativaEm()).isEqualTo(agora.plusSeconds(2));
    assertThat(service.processarLote()).as("ainda não venceu").isZero();
  }

  @Test
  void retryAfterDefineAProximaTentativa() {
    var s = pendente();
    respostas.add(new Indisponivel("HTTP 503", Duration.ofSeconds(45)));
    service.processarLote();
    assertThat(solicitacaoAdapter.atual(s.id()).proximaTentativaEm())
        .isEqualTo(agora.plusSeconds(45));
  }

  @Test
  void timeoutRepeteComAMesmaChave() {
    var s = pendente();
    respostas.add(new SemConfirmacao("timeout"));
    service.processarLote();
    assertThat(solicitacaoAdapter.atual(s.id()).status()).isEqualTo(PENDING);

    var depois = Clock.fixed(agora.plusSeconds(10), ZoneOffset.UTC);
    respostas.add(new Ativado());
    new ProcessarSolicitacoesService(solicitacaoAdapter, provedor, transacao, politica, depois, 10)
        .processarLote();
    assertThat(pedidos).extracting(Pedido::chaveIdempotencia).containsExactly(s.id(), s.id());
    assertThat(solicitacaoAdapter.atual(s.id()).status()).isEqualTo(COMPLETED);
    assertThat(solicitacaoAdapter.atual(s.id()).tentativas()).isEqualTo(2);
  }

  @Test
  void esgotarTentativasFalhaDeVez() {
    var s = pendente();
    for (int i = 0; i < 3; i++) {
      respostas.add(new Indisponivel("HTTP 500", null));
      new ProcessarSolicitacoesService(
              solicitacaoAdapter,
              provedor,
              transacao,
              politica,
              Clock.fixed(agora.plus(Duration.ofHours(i)), ZoneOffset.UTC),
              10)
          .processarLote();
    }
    var atual = solicitacaoAdapter.atual(s.id());
    assertThat(atual.status()).isEqualTo(FAILED);
    assertThat(atual.tentativas()).isEqualTo(3);
    assertThat(atual.ultimoErro()).startsWith("Tentativas esgotadas");
  }

  @Test
  void conflitoDeVersaoNoDesfechoPreservaOEstadoConcorrente() {
    var s = pendente();
    ProvedorAtivacao provedorLento =
        pedido -> {
          // Enquanto a chamada demora, a reconciliação devolve a solicitação para a fila.
          var concorrente = solicitacaoAdapter.atual(pedido.chaveIdempotencia());
          concorrente.devolverParaFila(agora, agora, "reconciliada");
          solicitacaoAdapter.atualizar(concorrente);
          return new Ativado();
        };
    new ProcessarSolicitacoesService(
            solicitacaoAdapter, provedorLento, transacao, politica, clock, 10)
        .processarLote();
    var atual = solicitacaoAdapter.atual(s.id());
    assertThat(atual.status()).isEqualTo(PENDING);
    assertThat(atual.ultimoErro()).isEqualTo("reconciliada");
  }
}
