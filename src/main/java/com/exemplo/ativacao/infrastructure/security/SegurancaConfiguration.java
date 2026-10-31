package com.exemplo.ativacao.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server sem estado: cada requisição traz um JWT obtido por client credentials. Escrita e
 * leitura exigem escopos distintos; 401 e 403 seguem o mesmo Problem Details da API.
 */
@Configuration
@EnableConfigurationProperties(SegurancaProperties.class)
public class SegurancaConfiguration {
  public static final String ESCOPO_ESCRITA = "SCOPE_activation:write";
  public static final String ESCOPO_LEITURA = "SCOPE_activation:read";

  @Bean
  SecurityFilterChain api(HttpSecurity http, ObjectMapper json) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/arquitetura.html",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/requests")
                    .hasAuthority(ESCOPO_ESCRITA)
                    .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
                    .hasAuthority(ESCOPO_LEITURA)
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            rs ->
                rs.jwt(jwt -> {})
                    .authenticationEntryPoint(
                        (req, res, e) ->
                            escrever(
                                json,
                                req,
                                res,
                                HttpStatus.UNAUTHORIZED,
                                "unauthorized",
                                "Não autenticado",
                                "Envie um token Bearer válido."))
                    .accessDeniedHandler(
                        (req, res, e) ->
                            escrever(
                                json,
                                req,
                                res,
                                HttpStatus.FORBIDDEN,
                                "forbidden",
                                "Acesso negado",
                                "O token não tem o escopo exigido para esta operação.")))
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder(SegurancaProperties props) {
    NimbusJwtDecoder decoder =
        props.jwkSetUri() != null && !props.jwkSetUri().isBlank()
            ? NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build()
            : NimbusJwtDecoder.withSecretKey(
                    new SecretKeySpec(
                        props.chaveHmac().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    // Assinatura é verificada pelo decoder; aqui entram expiração (com folga), emissor e audiência.
    OAuth2TokenValidator<Jwt> audiencia =
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(props.audiencia())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Audiência inválida", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(props.emissor()), audiencia));
    return decoder;
  }

  private static void escrever(
      ObjectMapper json,
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String type,
      String title,
      String detail)
      throws IOException {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://api.exemplo.com/problems/" + type));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("correlationId");
    if (traceId != null) problem.setProperty("traceId", traceId);
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (status == HttpStatus.UNAUTHORIZED)
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    json.writeValue(response.getOutputStream(), problem);
  }
}
