package com.exemplo.ativacao.core.application.port.in;

public interface RegistrarSolicitacaoUseCase {
  Resultado executar(Comando comando);

  /** {@code correlationId} vem da requisição de origem e segue até a chamada ao provedor. */
  record Comando(String externalId, String customerId, String productCode, String correlationId) {}

  /** Cada desfecho de um registro; o chamador decide a resposta com switch exaustivo. */
  sealed interface Resultado {}

  /** Primeira vez que o externalId aparece. */
  record Criada(SolicitacaoView solicitacao) implements Resultado {}

  /** Mesmo externalId e mesmo conteúdo: retry legítimo do sistema de origem. */
  record Repetida(SolicitacaoView solicitacao) implements Resultado {}

  /** Mesmo externalId com conteúdo diferente do registrado. */
  record Conflitante(String externalId) implements Resultado {}
}
