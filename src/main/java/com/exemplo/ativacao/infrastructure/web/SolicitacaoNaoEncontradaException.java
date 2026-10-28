package com.exemplo.ativacao.infrastructure.web;

import java.util.UUID;

class SolicitacaoNaoEncontradaException extends RuntimeException {
  SolicitacaoNaoEncontradaException(UUID id) {
    super("Solicitação " + id + " não encontrada");
  }
}
