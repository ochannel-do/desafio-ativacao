package com.exemplo.ativacao.infrastructure.web;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Logs inside Spring's HTTP observation scope, after MVC has resolved handled exceptions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {
  public static final String BUSINESS_CODE_ATTRIBUTE =
      HttpRequestLoggingFilter.class.getName() + ".code";
  private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
  private final ObjectProvider<Tracer> tracers;

  public HttpRequestLoggingFilter(ObjectProvider<Tracer> tracers) {
    this.tracers = tracers;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    var tracer = tracers.getIfAvailable();
    var span = tracer == null ? null : tracer.currentSpan();
    String correlationId = span == null ? UUID.randomUUID().toString() : span.context().traceId();
    String previous = MDC.get("correlationId");
    MDC.put("correlationId", correlationId);
    response.setHeader("X-Correlation-Id", correlationId);
    boolean failed = false;
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error e) {
      failed = true;
      throw e;
    } finally {
      try {
        // A route template avoids logging personal data embedded in arbitrary URLs or query
        // strings.
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object code = request.getAttribute(BUSINESS_CODE_ATTRIBUTE);
        log.atInfo()
            .addKeyValue("event", "http_request_completed")
            .addKeyValue("method", request.getMethod())
            .addKeyValue("path", route == null ? "UNMATCHED" : route.toString())
            .addKeyValue("status", failed ? 500 : response.getStatus())
            .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            .addKeyValue("businessCode", code == null ? "NONE" : code)
            .log("HTTP request completed");
      } finally {
        if (previous == null) MDC.remove("correlationId");
        else MDC.put("correlationId", previous);
      }
    }
  }
}
