package com.exemplo.ativacao.core.application.port.in;

public interface RegistrarSolicitacaoUseCase {
  Resultado executar(Comando comando);

  /**
   * {@code consumidor} é o sistema autenticado que envia a solicitação. {@code correlationId} vem
   * da requisição de origem e segue até a chamada ao provedor.
   */
  record Comando(
      String consumidor,
      String externalId,
      String customerId,
      String productCode,
      String correlationId) {}

  /** Cada desfecho de um registro; o chamador decide a resposta com switch exaustivo. */
  sealed interface Resultado {}

  /** Primeira vez que o externalId aparece. */
  record Criada(SolicitacaoView solicitacao) implements Resultado {}

  /** Mesmo externalId e mesmo conteúdo: retry legítimo do sistema de origem. */
  record Repetida(SolicitacaoView solicitacao) implements Resultado {}

  /** Mesmo externalId com conteúdo diferente do registrado, ou registrado por outro consumidor. */
  record Conflitante(String externalId) implements Resultado {}
}
