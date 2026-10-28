package com.exemplo.ativacao.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Erros em Problem Details (RFC 9457) com {@code code} estável e {@code traceId} para achar o log.
 * Exceções padrão do Spring MVC (405, 415, JSON malformado) vêm da classe base, nunca caem no 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String TYPE_BASE = "https://api.exemplo.com/problems/";

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var problem = validacao();
    problem.setProperty(
        "errors",
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field", f.getField(), "message", mensagem(f.getDefaultMessage())))
            .toList());
    return handleExceptionInternal(e, problem, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var problem = validacao();
    problem.setProperty(
        "errors",
        e.getParameterValidationResults().stream()
            .flatMap(
                r ->
                    r.getResolvableErrors().stream()
                        .map(
                            err ->
                                Map.of(
                                    "field",
                                    String.valueOf(r.getMethodParameter().getParameterName()),
                                    "message",
                                    mensagem(err.getDefaultMessage()))))
            .toList());
    return handleExceptionInternal(e, problem, headers, status, request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
    var problem = validacao();
    problem.setProperty(
        "errors", List.of(Map.of("field", e.getName(), "message", "valor inválido")));
    return comTrace(problem);
  }

  @ExceptionHandler(SolicitacaoNaoEncontradaException.class)
  ProblemDetail naoEncontrada(SolicitacaoNaoEncontradaException e) {
    return comTrace(
        problem(
            HttpStatus.NOT_FOUND,
            "not-found",
            "Solicitação não encontrada",
            "Não existe solicitação com o identificador informado."));
  }

  @ExceptionHandler(ExternalIdConflitanteException.class)
  ProblemDetail conflito(ExternalIdConflitanteException e, HttpServletRequest request) {
    request.setAttribute(HttpRequestLoggingFilter.BUSINESS_CODE_ATTRIBUTE, "DUPLICATE_CONFLICT");
    return comTrace(
        problem(
            HttpStatus.CONFLICT,
            "duplicate-conflict",
            "Solicitação duplicada",
            "O externalId já foi registrado com customerId ou productCode diferentes."));
  }

  @ExceptionHandler(TransientDataAccessException.class)
  ProblemDetail indisponivel(TransientDataAccessException e) {
    log.warn("Banco indisponível ao processar requisição", e);
    return comTrace(
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "service-unavailable",
            "Serviço indisponível",
            "Tente novamente em instantes."));
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail inesperado(Exception e) {
    log.error("Falha inesperada ao processar requisição", e);
    return comTrace(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Erro interno",
            "Não foi possível processar a requisição."));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (body instanceof ProblemDetail p) comTrace(p);
    return super.handleExceptionInternal(e, body, headers, status, request);
  }

  private static ProblemDetail validacao() {
    return problem(
        HttpStatus.BAD_REQUEST,
        "validation-error",
        "Dados inválidos",
        "Um ou mais campos não passaram na validação.");
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail) {
    var p = ProblemDetail.forStatusAndDetail(status, detail);
    p.setType(URI.create(TYPE_BASE + type));
    p.setTitle(title);
    return p;
  }

  private static ProblemDetail comTrace(ProblemDetail p) {
    String traceId = MDC.get("correlationId");
    if (traceId != null) p.setProperty("traceId", traceId);
    return p;
  }

  private static String mensagem(@Nullable String message) {
    return message == null ? "inválido" : message;
  }
}
