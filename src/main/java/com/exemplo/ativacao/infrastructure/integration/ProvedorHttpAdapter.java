package com.exemplo.ativacao.infrastructure.integration;

import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Traduz HTTP para as respostas da porta. O circuit breaker conta só falhas do provedor (5xx, 429,
 * timeout, conexão); uma recusa de negócio (4xx) é resposta válida e não abre o circuito.
 */
public final class ProvedorHttpAdapter implements ProvedorAtivacao {
  private static final Logger log = LoggerFactory.getLogger(ProvedorHttpAdapter.class);
  private final RestClient client;
  private final CircuitBreaker circuitBreaker;
  private final Clock clock;
  private final MeterRegistry metricas;

  public ProvedorHttpAdapter(
      RestClient client, CircuitBreaker circuitBreaker, Clock clock, MeterRegistry metricas) {
    this.client = client;
    this.circuitBreaker = circuitBreaker;
    this.clock = clock;
    this.metricas = metricas;
  }

  /** HTTP/1.1 explícito: evita o upgrade h2c que alguns provedores e proxies tratam mal. */
  public static RestClient cliente(RestClient.Builder builder, ProvedorProperties props) {
    var http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(props.connectTimeout())
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(props.readTimeout());
    return builder
        .baseUrl(props.baseUrl())
        .requestFactory(factory)
        .defaultHeaders(
            h -> {
              if (props.apiKey() != null && !props.apiKey().isBlank())
                h.setBearerAuth(props.apiKey());
            })
        .build();
  }

  public Resposta ativar(Pedido pedido) {
    Resposta resposta;
    try {
      resposta =
          circuitBreaker.executeSupplier(
              () -> {
                var r = chamar(pedido);
                if (r instanceof Indisponivel || r instanceof SemConfirmacao)
                  throw new FalhaDoProvedor(r);
                return r;
              });
    } catch (FalhaDoProvedor e) {
      resposta = e.resposta;
    } catch (CallNotPermittedException e) {
      resposta = new Indisponivel("Circuito aberto: provedor em recuperação", null);
    }
    // Taxa de erro do provedor por desfecho; latência vem de http.client.requests.
    metricas
        .counter("ativacao.provedor.respostas", "resultado", resposta.getClass().getSimpleName())
        .increment();
    log.atInfo()
        .addKeyValue("event", "provider_activation_called")
        .addKeyValue("requestId", pedido.chaveIdempotencia())
        .addKeyValue("correlationId", pedido.correlationId())
        .addKeyValue("outcome", resposta.getClass().getSimpleName())
        .log("Chamada ao provedor de ativação");
    return resposta;
  }

  private Resposta chamar(Pedido pedido) {
    try {
      return client
          .post()
          .uri("/provider/v1/activate")
          .contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", pedido.chaveIdempotencia().toString())
          .headers(
              h -> {
                if (pedido.correlationId() != null)
                  h.set("X-Correlation-Id", pedido.correlationId());
              })
          .body(
              Map.of(
                  "requestId", pedido.chaveIdempotencia(),
                  "externalId", pedido.externalId(),
                  "customerId", pedido.customerId(),
                  "productCode", pedido.productCode()))
          .exchange(
              (request, response) -> classificar(response.getStatusCode(), response.getHeaders()));
    } catch (ResourceAccessException e) {
      log.atWarn()
          .addKeyValue("event", "provider_io_error")
          .addKeyValue("requestId", pedido.chaveIdempotencia())
          .setCause(e)
          .log("Falha de comunicação com o provedor");
      return timeout(e)
          ? new SemConfirmacao("Timeout aguardando o provedor")
          : new Indisponivel("Falha de conexão com o provedor", null);
    }
  }

  Resposta classificar(HttpStatusCode status, HttpHeaders headers) {
    int code = status.value();
    if (status.is2xxSuccessful()) return new Ativado();
    if (code == 429 || code == 503)
      return new Indisponivel("Provedor respondeu HTTP " + code, retryAfter(headers));
    if (status.is5xxServerError()) return new Indisponivel("Provedor respondeu HTTP " + code, null);
    // 401/403 indicam credencial nossa inválida, e 408 é timeout do lado do provedor:
    // nada disso é culpa da solicitação, então não a falhamos de vez.
    if (code == 401 || code == 403 || code == 408)
      return new Indisponivel("Provedor respondeu HTTP " + code, null);
    return new Rejeitado("Provedor recusou a ativação: HTTP " + code);
  }

  private Duration retryAfter(HttpHeaders headers) {
    String valor = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (valor == null) return null;
    try {
      return Duration.ofSeconds(Math.max(0, Long.parseLong(valor.trim())));
    } catch (NumberFormatException e) {
      try {
        var data = ZonedDateTime.parse(valor.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
        var espera = Duration.between(clock.instant(), data.toInstant());
        return espera.isNegative() ? Duration.ZERO : espera;
      } catch (RuntimeException ignorado) {
        return null;
      }
    }
  }

  private static boolean timeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) return true;
    return false;
  }

  private static final class FalhaDoProvedor extends RuntimeException {
    final transient Resposta resposta;

    FalhaDoProvedor(Resposta resposta) {
      super(null, null, false, false);
      this.resposta = resposta;
    }
  }
}
