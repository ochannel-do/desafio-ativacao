package com.exemplo.ativacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.exemplo.ativacao.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureObservability(tracing = false)
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
        .isEqualTo(401);
  }

  @Test
  void expoeMetricasParaPrometheus() {
    var response = http.getForEntity("/actuator/prometheus", String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("jvm_memory_used_bytes");
  }
}
