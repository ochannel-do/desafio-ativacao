package com.exemplo.ativacao.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI openApi(@Value("${ativacao.documentacao.token-url:}") String tokenUrl) {
    boolean usaKeycloak = !tokenUrl.isBlank();
    OpenAPI api =
        new OpenAPI()
            .addServersItem(new Server().url("/"))
            .info(
                new Info()
                    .title("API de ativação de produtos")
                    .version("v1")
                    .description(
                        usaKeycloak
                            ? """
                          Registre e consulte solicitações de ativação. Cada empresa visualiza
                          apenas suas próprias solicitações.

                          Clique em **Authorize**, escolha **keycloak** e informe o `client_id`
                          (`empresa-a` ou `empresa-b`) e seu `client_secret` (senha da empresa).
                          As permissões de leitura e escrita já estão selecionadas.
                          O Swagger solicita o token ao Keycloak e o envia nas chamadas à API.
                          Quando o token expirar, autorize novamente. Para trocar de empresa,
                          use **Logout** e autorize com as credenciais da outra empresa.
                          """
                            : """
                    Registre e consulte solicitações de ativação. Use um JWT com
                    `activation:write` para criar e `activation:read` para consultar.
                    Cada consumidor visualiza apenas suas próprias solicitações.

                    ### Gerar token local

                    Com a aplicação no perfil `local`, execute na raiz do projeto:

                    ```sh
                    scripts/token-local.sh sistema-a "activation:write activation:read"
                    ```

                    Copie o token retornado, clique em **Authorize** e cole somente o JWT,
                    sem o prefixo `Bearer`. Depois use **Try it out** e **Execute** na operação desejada.
                    Se o token expirar, gere outro e atualize em **Authorize**.
                    """))
            .components(new Components());
    if (usaKeycloak) {
      api.getComponents()
          .addSecuritySchemes(
              "keycloak",
              new SecurityScheme()
                  .type(SecurityScheme.Type.OAUTH2)
                  .description("Informe o identificador e o segredo da sua empresa.")
                  .flows(
                      new OAuthFlows()
                          .clientCredentials(
                              new OAuthFlow()
                                  .tokenUrl(tokenUrl)
                                  .scopes(
                                      new Scopes()
                                          .addString("activation:read", "Consultar solicitações")
                                          .addString("activation:write", "Criar solicitações")))));
      api.addSecurityItem(
          new SecurityRequirement()
              .addList("keycloak", List.of("activation:read", "activation:write")));
    } else {
      api.getComponents()
          .addSecuritySchemes(
              "bearerAuth",
              new SecurityScheme()
                  .type(SecurityScheme.Type.HTTP)
                  .scheme("bearer")
                  .bearerFormat("JWT")
                  .description("Cole somente o JWT, sem o prefixo Bearer."));
      api.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
    return api;
  }
}
