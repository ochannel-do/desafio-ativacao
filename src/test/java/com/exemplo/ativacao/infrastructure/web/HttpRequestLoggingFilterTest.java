package com.exemplo.ativacao.infrastructure.web;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpRequestLoggingFilterTest {
  @Test
  void healthChecksNaoGeramEventosMasPedidosContinuamRegistrados() throws Exception {
    var filter =
        new HttpRequestLoggingFilter(
            new DefaultListableBeanFactory().getBeanProvider(Tracer.class));
    var logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      for (String path :
          new String[] {
            "/actuator/health",
            "/actuator/health/",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
          }) {
        var request = new MockHttpServletRequest("GET", "/ativacao" + path);
        request.setContextPath("/ativacao");
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
      }
      assertThat(events.list).isEmpty();
      for (String path : new String[] {"/api/v1/requests", "/actuator/healthcheck"}) {
        filter.doFilter(
            new MockHttpServletRequest("POST", path),
            new MockHttpServletResponse(),
            (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(409));
      }
      assertThat(events.list).hasSize(2);
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void fallbackELimpezaMesmoComExcecao() throws Exception {
    var filter =
        new HttpRequestLoggingFilter(
            new DefaultListableBeanFactory().getBeanProvider(Tracer.class));
    var logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    var events =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    events.start();
    logger.addAppender(events);
    String previous = MDC.get("correlationId");
    try {
      MDC.remove("correlationId");
      var first = new MockHttpServletResponse();
      filter.doFilter(
          new MockHttpServletRequest("GET", "/private@example.com"),
          first,
          (request, response) ->
              assertThat(MDC.get("correlationId")).isEqualTo(first.getHeader("X-Correlation-Id")));
      assertThat(MDC.get("correlationId")).isNull();
      MDC.put("correlationId", "outer-context");
      var second = new MockHttpServletResponse();
      assertThatThrownBy(
              () ->
                  filter.doFilter(
                      new MockHttpServletRequest("POST", "/api/v1/requests"),
                      second,
                      (request, response) -> {
                        throw new ServletException("falha");
                      }))
          .isInstanceOf(ServletException.class);
      assertThat(MDC.get("correlationId")).isEqualTo("outer-context");
      assertThat(first.getHeader("X-Correlation-Id"))
          .isNotEqualTo(second.getHeader("X-Correlation-Id"));
      assertThat(events.list).hasSize(2);
      assertThat(events.list.getFirst().getMDCPropertyMap().get("correlationId"))
          .isEqualTo(first.getHeader("X-Correlation-Id"));
      assertThat(events.list.getLast().getKeyValuePairs())
          .anySatisfy(
              kv -> {
                assertThat(kv.key).isEqualTo("status");
                assertThat(kv.value).isEqualTo(500);
              });
      assertThat(events.list.getFirst().getKeyValuePairs())
          .anySatisfy(
              kv -> {
                assertThat(kv.key).isEqualTo("path");
                assertThat(kv.value).isEqualTo("UNMATCHED");
              });
    } finally {
      logger.detachAppender(events);
      events.stop();
      if (previous == null) MDC.remove("correlationId");
      else MDC.put("correlationId", previous);
    }
  }
}
