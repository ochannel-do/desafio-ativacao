package com.exemplo.ativacao;

import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.support.PostgresTestConfiguration;
import com.exemplo.ativacao.support.Tokens;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Requisições HTTP reais e PostgreSQL real: a garantia vem da constraint, não do teste. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class DuplicidadeConcorrenteIT {
  static final int PARALELAS = 16;
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void limpar() {
    jdbc.execute("truncate activation_inbox, activation_outbox, activation_request");
  }

  ResponseEntity<Map> enviar(String productCode) {
    return enviar("sistema-a", productCode);
  }

  ResponseEntity<Map> enviar(String clientId, String productCode) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(Tokens.para(clientId, "activation:write"));
    return http.postForEntity(
        "/api/v1/requests",
        new HttpEntity<>(
            Map.of(
                "externalId", "EXT-CONC", "customerId", "CUST-10001", "productCode", productCode),
            headers),
        Map.class);
  }

  @Test
  void requisicoesIdenticasSimultaneasGeramUmRegistro() throws Exception {
    var largada = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(PARALELAS)) {
      var futuros = new ArrayList<Future<ResponseEntity<Map>>>();
      for (int i = 0; i < PARALELAS; i++)
        futuros.add(
            executor.submit(
                () -> {
                  largada.await();
                  return enviar("INTERNET-500");
                }));
      largada.countDown();
      var respostas = new ArrayList<ResponseEntity<Map>>();
      for (var f : futuros) respostas.add(f.get(30, TimeUnit.SECONDS));

      assertThat(respostas)
          .extracting(r -> r.getStatusCode().value())
          .containsOnly(201, 200)
          .filteredOn(s -> s == 201)
          .hasSize(1);
      assertThat(respostas)
          .extracting(r -> r.getBody().get("id"))
          .containsOnly(respostas.getFirst().getBody().get("id"));
    }
    assertThat(jdbc.queryForObject("select count(*) from activation_request", Long.class))
        .isEqualTo(1);
  }

  @Test
  void outroConsumidorComOMesmoExternalIdNaoRecebeORegistroAlheio() {
    var original = enviar("sistema-a", "INTERNET-500");
    var deOutro = enviar("sistema-b", "INTERNET-500");
    assertThat(deOutro.getStatusCode().value()).isEqualTo(409);
    assertThat(deOutro.getBody()).doesNotContainKey("id");
    assertThat(deOutro.getBody().toString())
        .doesNotContain(original.getBody().get("id").toString());
  }

  @Test
  void mesmoExternalIdComOutroProdutoRetornaConflito() {
    assertThat(enviar("INTERNET-500").getStatusCode().value()).isEqualTo(201);
    var conflito = enviar("TV-100");
    assertThat(conflito.getStatusCode().value()).isEqualTo(409);
    assertThat(conflito.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(jdbc.queryForObject("select product_code from activation_request", String.class))
        .isEqualTo("INTERNET-500");
  }
}
