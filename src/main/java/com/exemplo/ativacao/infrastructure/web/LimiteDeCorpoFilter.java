package com.exemplo.ativacao.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recusa corpos acima do limite antes de autenticar ou desserializar. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LimiteDeCorpoFilter extends OncePerRequestFilter {
  static final long LIMITE_BYTES = 16 * 1024;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > LIMITE_BYTES) {
      response.setStatus(413);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"https://api.exemplo.com/problems/payload-too-large\","
                  + "\"title\":\"Corpo muito grande\",\"status\":413,"
                  + "\"detail\":\"O corpo da requisição excede 16 KB.\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
