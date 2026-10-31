package com.exemplo.ativacao.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenApiConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(OpenApiConfiguration.class);

  @Test
  void homologacaoPermiteObterTokenComCredenciaisDaEmpresaSemPublicarSegredo() {
    String tokenUrl =
        "https://keycloak.homelab/realms/desafio-ativacao/protocol/openid-connect/token";
    context
        .withPropertyValues("ativacao.documentacao.token-url=" + tokenUrl)
        .run(
            ctx -> {
              var api = ctx.getBean(OpenAPI.class);
              var oauth = api.getComponents().getSecuritySchemes().get("keycloak");
              assertThat(oauth).isNotNull();
              assertThat(api.getServers()).extracting(Server::getUrl).containsExactly("/");
              assertThat(oauth.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);
              var flow = oauth.getFlows().getClientCredentials();
              assertThat(flow.getTokenUrl()).isEqualTo(tokenUrl);
              assertThat(flow.getScopes().keySet())
                  .containsExactlyInAnyOrder("activation:read", "activation:write");
              assertThat(api.getSecurity())
                  .anySatisfy(requirement -> assertThat(requirement).containsKey("keycloak"))
                  .anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
              assertThat(api.getInfo().getDescription())
                  .contains("client_id", "client_secret", "Authorize")
                  .doesNotContain("token-local.sh");
              assertThat(api.toString()).doesNotContain("clientSecret=");
            });
  }

  @Test
  void semServidorDeTokensPreservaAutorizacaoManualParaDesenvolvimentoLocal() {
    context.run(
        ctx -> {
          var api = ctx.getBean(OpenAPI.class);
          assertThat(api.getComponents().getSecuritySchemes()).containsOnlyKeys("bearerAuth");
          assertThat(api.getInfo().getDescription()).contains("token-local.sh");
        });
  }
}
