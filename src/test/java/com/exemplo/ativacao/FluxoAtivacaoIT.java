package com.exemplo.ativacao;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.exemplo.ativacao.support.PostgresTestConfiguration;
import com.exemplo.ativacao.support.Tokens;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Do POST ao status final, com o agendador real, PostgreSQL real e provedor simulado. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ativacao.processamento.habilitado=true",
      "ativacao.processamento.intervalo=100ms",
      "ativacao.processamento.backoff-base=100ms",
      "ativacao.processamento.backoff-teto=200ms",
      "ativacao.processamento.intervalo-reconciliacao=1h",
      "ativacao.provedor.read-timeout=500ms"
    })
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class FluxoAtivacaoIT {
  static WireMockServer provedor = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    provedor.start();
  }

  @DynamicPropertySource
  static void provedor(DynamicPropertyRegistry registry) {
    registry.add("ativacao.provedor.base-url", provedor::baseUrl);
  }

  @AfterAll
  static void parar() {
    provedor.stop();
  }

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void limpar() {
    provedor.resetAll();
    jdbc.execute("truncate activation_inbox, activation_outbox, activation_request");
  }

  ResponseEntity<Map> criar(String externalId) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(Tokens.para("sistema-a", "activation:write"));
    return http.postForEntity(
        "/api/v1/requests",
        new HttpEntity<>(
            Map.of("externalId", externalId, "customerId", "CUST-1", "productCode", "INTERNET-500"),
            headers),
        Map.class);
  }

  Map<?, ?> aguardarStatus(Object id, String status) {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              var headers = new HttpHeaders();
              headers.setBearerAuth(Tokens.para("sistema-a", "activation:read"));
              return http.exchange(
                      "/api/v1/requests/" + id,
                      HttpMethod.GET,
                      new HttpEntity<>(headers),
                      Map.class)
                  .getBody();
            },
            body -> status.equals(body.get("status")));
  }

  @Test
  void sucessoConcluiEPropagaCorrelacaoAteOProvedor() {
    provedor.stubFor(post("/provider/v1/activate").willReturn(ok()));
    var criada = criar("EXT-OK");
    assertThat(criada.getStatusCode().value()).isEqualTo(201);
    assertThat(criada.getBody().get("status")).isEqualTo("PENDING");

    var final_ = aguardarStatus(criada.getBody().get("id"), "COMPLETED");
    assertThat(final_.get("attempts")).isEqualTo(1);
    provedor.verify(
        postRequestedFor(urlEqualTo("/provider/v1/activate"))
            .withHeader("Idempotency-Key", equalTo(criada.getBody().get("id").toString()))
            .withHeader(
                "X-Correlation-Id", equalTo(criada.getHeaders().getFirst("X-Correlation-Id"))));
  }

  @Test
  void recusaDoProvedorFalhaSemRetentar() {
    provedor.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(400)));
    var id = criar("EXT-400").getBody().get("id");
    var final_ = aguardarStatus(id, "FAILED");
    assertThat(final_.get("lastError")).asString().contains("400");
    assertThat(final_.get("attempts")).isEqualTo(1);
  }

  @Test
  void indisponibilidadeTemporariaSeRecuperaNaTentativaSeguinte() {
    provedor.stubFor(
        post("/provider/v1/activate")
            .inScenario("instavel")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recuperado"));
    provedor.stubFor(
        post("/provider/v1/activate")
            .inScenario("instavel")
            .whenScenarioStateIs("recuperado")
            .willReturn(aResponse().withStatus(500).withFixedDelay(1_000))
            .willSetStateTo("saudavel"));
    provedor.stubFor(
        post("/provider/v1/activate")
            .inScenario("instavel")
            .whenScenarioStateIs("saudavel")
            .willReturn(ok()));

    var id = criar("EXT-INSTAVEL").getBody().get("id");
    var final_ = aguardarStatus(id, "COMPLETED");
    // 503, depois timeout (pode ter ativado), depois sucesso: sempre a mesma chave.
    assertThat(final_.get("attempts")).isEqualTo(3);
    provedor.verify(
        3,
        postRequestedFor(urlEqualTo("/provider/v1/activate"))
            .withHeader("Idempotency-Key", equalTo(id.toString())));
  }
}
