package com.exemplo.ativacao.infrastructure.web;

class ExternalIdConflitanteException extends RuntimeException {
  ExternalIdConflitanteException(String externalId) {
    super("externalId " + externalId + " já registrado com outro conteúdo");
  }
}
