package com.exemplo.ativacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.exemplo.ativacao.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.tracing.enabled=false", "ativacao.processamento.habilitado=false"})
@Import(PostgresTestConfiguration.class)
class ApplicationIT {
  @Autowired TestRestTemplate http;

  @Test
  void probesDeLivenessEReadinessRespondemUp() {
    for (String probe : new String[] {"liveness", "readiness"}) {
      var response = http.getForEntity("/actuator/health/" + probe, String.class);
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
  }

  @Test
  void naoExpoeEndpointsSensiveisDoActuator() {
    assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode().value())
        .isEqualTo(404);
  }
}
