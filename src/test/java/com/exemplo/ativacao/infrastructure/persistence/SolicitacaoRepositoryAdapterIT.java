package com.exemplo.ativacao.infrastructure.persistence;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository.ConflitoDeVersaoException;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.support.PostgresTestConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "management.tracing.enabled=false")
@Import(PostgresTestConfiguration.class)
class SolicitacaoRepositoryAdapterIT {
  @Autowired SolicitacaoRepository repository;
  @Autowired JdbcTemplate jdbc;
  final Instant t0 = Instant.parse("2026-10-26T14:03:11.123456Z");

  @BeforeEach
  void limpar() {
    jdbc.execute("truncate activation_request");
  }

  SolicitacaoAtivacao nova(String externalId, String customer, Instant criadaEm) {
    return SolicitacaoAtivacao.nova(
        UUID.randomUUID(), externalId, customer, "INTERNET-500", criadaEm);
  }

  @Test
  void insereUmaVezPorExternalIdEPreservaMicrossegundos() {
    var primeira = nova("EXT-1", "CUST-1", t0);
    assertThat(repository.inserirSeAusente(primeira)).isTrue();
    assertThat(repository.inserirSeAusente(nova("EXT-1", "CUST-2", t0))).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from activation_request", Long.class))
        .isEqualTo(1);
    var lida = repository.porExternalId("EXT-1").orElseThrow();
    assertThat(lida.id()).isEqualTo(primeira.id());
    assertThat(lida.customerId()).isEqualTo("CUST-1");
    assertThat(lida.createdAt()).isEqualTo(t0);
    assertThat(repository.porId(primeira.id())).isPresent();
    assertThat(repository.porId(UUID.randomUUID())).isEmpty();
  }

  @Test
  void bancoRejeitaDuplicidadeMesmoForaDaAplicacao() {
    repository.inserirSeAusente(nova("EXT-1", "CUST-1", t0));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into activation_request (id, external_id, customer_id, product_code,"
                        + " status, created_at, updated_at) values (?, 'EXT-1', 'C', 'P',"
                        + " 'PENDING', now(), now())",
                    UUID.randomUUID()))
        .hasMessageContaining("uk_activation_request_external_id");
  }

  @Test
  void atualizacaoComVersaoDesatualizadaFalha() {
    var original = nova("EXT-1", "CUST-1", t0);
    repository.inserirSeAusente(original);
    var leituraA = repository.porId(original.id()).orElseThrow();
    var leituraB = repository.porId(original.id()).orElseThrow();

    leituraA.iniciarProcessamento(t0.plusSeconds(1));
    repository.atualizar(leituraA);

    leituraB.iniciarProcessamento(t0.plusSeconds(2));
    assertThatThrownBy(() -> repository.atualizar(leituraB))
        .isInstanceOf(ConflitoDeVersaoException.class);

    var atual = repository.porId(original.id()).orElseThrow();
    assertThat(atual.status()).isEqualTo(PROCESSING);
    assertThat(atual.tentativas()).isEqualTo(1);
    assertThat(atual.versao()).isEqualTo(1);
    assertThat(atual.updatedAt()).isEqualTo(t0.plusSeconds(1));
  }

  @Test
  void listaPorFiltroDoMaisRecenteParaOMaisAntigo() {
    for (int i = 0; i < 5; i++)
      repository.inserirSeAusente(
          nova("EXT-" + i, i % 2 == 0 ? "CUST-A" : "CUST-B", t0.plus(i, ChronoUnit.MINUTES)));
    var processando = repository.porExternalId("EXT-4").orElseThrow();
    processando.iniciarProcessamento(t0.plusSeconds(600));
    repository.atualizar(processando);

    var clienteA = repository.listar(new Filtro(null, "CUST-A", 0, 2));
    assertThat(clienteA.total()).isEqualTo(3);
    assertThat(clienteA.itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-4", "EXT-2");
    assertThat(repository.listar(new Filtro(null, "CUST-A", 1, 2)).itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-0");

    var pendentesA = repository.listar(new Filtro(PENDING, "CUST-A", 0, 10));
    assertThat(pendentesA.itens())
        .extracting(SolicitacaoAtivacao::externalId)
        .containsExactly("EXT-2", "EXT-0");
    assertThat(repository.listar(new Filtro(null, null, 0, 10)).total()).isEqualTo(5);
  }
}
