package com.exemplo.ativacao.infrastructure.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

class ProvedorHttpAdapterTest {
  static WireMockServer provedor = new WireMockServer(wireMockConfig().dynamicPort());
  final Instant agora = Instant.parse("2026-10-26T14:00:00Z");
  final Pedido pedido =
      new Pedido(UUID.randomUUID(), "EXT-1", "CUST-1", "INTERNET-500", "trace-123");
  CircuitBreaker circuito;
  ProvedorHttpAdapter adapter;

  @BeforeAll
  static void iniciar() {
    provedor.start();
  }

  @AfterAll
  static void parar() {
    provedor.stop();
  }

  @BeforeEach
  void preparar() {
    provedor.resetAll();
    circuito =
        CircuitBreaker.of(
            "provedor",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    adapter = adapterPara(provedor.baseUrl());
  }

  ProvedorHttpAdapter adapterPara(String baseUrl) {
    var props =
        new ProvedorProperties(
            baseUrl, Duration.ofMillis(500), Duration.ofSeconds(1), "token-teste");
    var client = ProvedorHttpAdapter.cliente(RestClient.builder(), props);
    return new ProvedorHttpAdapter(client, circuito, Clock.fixed(agora, ZoneOffset.UTC));
  }

  void responder(int status) {
    provedor.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(status)));
  }

  @Test
  void sucessoEnviaIdempotenciaCorrelacaoECredencial() {
    responder(200);
    assertThat(adapter.ativar(pedido)).isEqualTo(new Ativado());
    provedor.verify(
        postRequestedFor(urlEqualTo("/provider/v1/activate"))
            .withHeader("Idempotency-Key", equalTo(pedido.chaveIdempotencia().toString()))
            .withHeader("X-Correlation-Id", equalTo("trace-123"))
            .withHeader("Authorization", equalTo("Bearer token-teste"))
            .withRequestBody(matchingJsonPath("$.externalId", equalTo("EXT-1")))
            .withRequestBody(matchingJsonPath("$.productCode", equalTo("INTERNET-500"))));
  }

  @Test
  void quatroXXDeNegocioEhRecusaDefinitiva() {
    for (int status : new int[] {400, 404, 409, 422}) {
      responder(status);
      assertThat(adapter.ativar(pedido)).isInstanceOf(Rejeitado.class);
    }
    assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void cincoXXECredencialRecusadaSaoTemporarios() {
    for (int status : new int[] {500, 502, 401, 408}) {
      circuito.reset();
      responder(status);
      assertThat(adapter.ativar(pedido))
          .isInstanceOfSatisfying(Indisponivel.class, r -> assertThat(r.aguardar()).isNull());
    }
  }

  @Test
  void retryAfterEmSegundosOuData() {
    provedor.stubFor(
        post("/provider/v1/activate")
            .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));
    assertThat(adapter.ativar(pedido))
        .isEqualTo(new Indisponivel("Provedor respondeu HTTP 503", Duration.ofSeconds(30)));
    provedor.stubFor(
        post("/provider/v1/activate")
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Retry-After", "Mon, 26 Oct 2026 14:02:00 GMT")));
    assertThat(adapter.ativar(pedido))
        .isEqualTo(new Indisponivel("Provedor respondeu HTTP 429", Duration.ofMinutes(2)));
  }

  @Test
  void timeoutDeLeituraEhSemConfirmacao() {
    provedor.stubFor(
        post("/provider/v1/activate")
            .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
    assertThat(adapter.ativar(pedido)).isInstanceOf(SemConfirmacao.class);
  }

  @Test
  void conexaoRecusadaOuDerrubadaEhIndisponivel() {
    provedor.stubFor(
        post("/provider/v1/activate")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    assertThat(adapter.ativar(pedido)).isInstanceOf(Indisponivel.class);
    assertThat(adapterPara("http://localhost:1").ativar(pedido)).isInstanceOf(Indisponivel.class);
  }

  @Test
  void circuitoAbreAposFalhasEParaDeChamarOProvedor() {
    responder(500);
    for (int i = 0; i < 4; i++) adapter.ativar(pedido);
    assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    provedor.resetRequests();
    assertThat(adapter.ativar(pedido))
        .isInstanceOfSatisfying(
            Indisponivel.class, r -> assertThat(r.motivo()).contains("Circuito aberto"));
    provedor.verify(0, postRequestedFor(urlEqualTo("/provider/v1/activate")));
  }
}
