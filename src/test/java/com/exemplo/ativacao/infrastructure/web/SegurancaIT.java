package com.exemplo.ativacao.infrastructure.web;

import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.support.PostgresTestConfiguration;
import com.exemplo.ativacao.support.Tokens;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

/**
 * Validação real do JWT (assinatura, emissor, audiência, expiração) sem servidor de autorização.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SegurancaIT {
  @Autowired TestRestTemplate http;

  @Test
  void documentacaoPublicaPermiteTestarComBearerSemExporPrincipal() {
    var ui = http.getForEntity("/swagger-ui/index.html", String.class);
    assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ui.getBody()).contains("Swagger UI");

    var config = http.getForEntity("/v3/api-docs/swagger-config", Map.class);
    assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(config.getBody().get("url")).isEqualTo("/v3/api-docs");

    var docs = http.getForEntity("/v3/api-docs", Map.class);
    assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
    var components = (Map<?, ?>) docs.getBody().get("components");
    var schemes = (Map<?, ?>) components.get("securitySchemes");
    assertThat((Map<String, Object>) schemes.get("bearerAuth"))
        .containsEntry("type", "http")
        .containsEntry("scheme", "bearer");
    var paths = (Map<?, ?>) docs.getBody().get("paths");
    assertThat(paths.keySet().stream().map(Object::toString).toList())
        .containsExactlyInAnyOrder("/api/v1/requests", "/api/v1/requests/{id}");
    var operations = (Map<?, ?>) paths.get("/api/v1/requests");
    var post = (Map<?, ?>) operations.get("post");
    assertThat(docs.getBody().get("security").toString()).contains("bearerAuth");
    assertThat(post.get("requestBody")).isNotNull();
    assertThat(docs.getBody().toString()).doesNotContain("Jwt");
  }

  int criar(String token, String externalId) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return http.postForEntity(
            "/api/v1/requests",
            new HttpEntity<>(
                Map.of("externalId", externalId, "customerId", "C", "productCode", "P"), headers),
            String.class)
        .getStatusCode()
        .value();
  }

  ResponseEntity<Map> consultar(String token, Object id) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return http.exchange(
        "/api/v1/requests/" + id, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  @Test
  void semTokenOuComTokenInvalidoRetorna401() {
    assertThat(criar(null, "EXT-S1")).isEqualTo(401);
    String expirado =
        Tokens.emitir(
            "sistema-a",
            Tokens.EMISSOR,
            Tokens.AUDIENCIA,
            Instant.now().minusSeconds(600),
            "activation:write");
    assertThat(criar(expirado, "EXT-S2")).isEqualTo(401);
    String outroEmissor =
        Tokens.emitir(
            "sistema-a",
            "https://malicioso",
            Tokens.AUDIENCIA,
            Instant.now().plusSeconds(60),
            "activation:write");
    assertThat(criar(outroEmissor, "EXT-S3")).isEqualTo(401);
    String outraAudiencia =
        Tokens.emitir(
            "sistema-a",
            Tokens.EMISSOR,
            "outra-api",
            Instant.now().plusSeconds(60),
            "activation:write");
    assertThat(criar(outraAudiencia, "EXT-S4")).isEqualTo(401);
    String adulterado = Tokens.para("sistema-a", "activation:write") + "x";
    assertThat(criar(adulterado, "EXT-S5")).isEqualTo(401);
  }

  @Test
  void escopoDeLeituraNaoCria() {
    assertThat(criar(Tokens.para("sistema-a", "activation:read"), "EXT-S6")).isEqualTo(403);
  }

  @Test
  void consumidorNaoEnxergaSolicitacaoDeOutro() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(Tokens.para("sistema-a", "activation:write"));
    var criada =
        http.postForEntity(
            "/api/v1/requests",
            new HttpEntity<>(
                Map.of("externalId", "EXT-S7", "customerId", "C", "productCode", "P"), headers),
            Map.class);
    var id = criada.getBody().get("id");

    assertThat(consultar(Tokens.para("sistema-a", "activation:read"), id).getStatusCode().value())
        .isEqualTo(200);
    assertThat(consultar(Tokens.para("sistema-b", "activation:read"), id).getStatusCode().value())
        .isEqualTo(404);
    var listaDeB =
        http.exchange(
            "/api/v1/requests",
            HttpMethod.GET,
            new HttpEntity<>(authorization(Tokens.para("sistema-b", "activation:read"))),
            Map.class);
    assertThat(listaDeB.getBody().get("totalElements")).isEqualTo(0);
  }

  static HttpHeaders authorization(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }
}
