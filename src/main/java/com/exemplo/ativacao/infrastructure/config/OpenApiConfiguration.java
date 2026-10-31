package com.exemplo.ativacao.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("API de ativação de produtos")
                .version("v1")
                .description(
                    "Registre e consulte solicitações de ativação. Use um JWT com "
                        + "activation:write para criar e activation:read para consultar. "
                        + "Cada consumidor visualiza apenas suas próprias solicitações."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Cole somente o JWT, sem o prefixo Bearer.")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
  }
}
