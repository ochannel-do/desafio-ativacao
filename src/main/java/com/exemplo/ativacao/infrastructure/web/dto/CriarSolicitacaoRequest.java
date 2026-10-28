package com.exemplo.ativacao.infrastructure.web.dto;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import jakarta.validation.constraints.*;

public record CriarSolicitacaoRequest(
    @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFICADOR, message = MENSAGEM_FORMATO)
        String externalId,
    @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFICADOR, message = MENSAGEM_FORMATO)
        String customerId,
    @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFICADOR, message = MENSAGEM_FORMATO)
        String productCode) {
  static final String IDENTIFICADOR = "[A-Za-z0-9][A-Za-z0-9._-]*";
  static final String MENSAGEM_FORMATO =
      "deve começar com letra ou dígito e conter apenas letras, dígitos, '.', '_' ou '-'";

  public RegistrarSolicitacaoUseCase.Comando comando(String correlationId) {
    return new RegistrarSolicitacaoUseCase.Comando(
        externalId, customerId, productCode, correlationId);
  }
}
