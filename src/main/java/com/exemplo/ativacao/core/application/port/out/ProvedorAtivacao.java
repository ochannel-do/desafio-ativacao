package com.exemplo.ativacao.core.application.port.out;

import java.time.Duration;
import java.util.UUID;

public interface ProvedorAtivacao {
  Resposta ativar(Pedido pedido);

  /** {@code chaveIdempotencia} é estável entre tentativas: o provedor ativa uma vez só. */
  record Pedido(
      UUID chaveIdempotencia,
      String externalId,
      String customerId,
      String productCode,
      String correlationId) {}

  sealed interface Resposta {}

  /** 2xx: produto ativado. */
  record Ativado() implements Resposta {}

  /** Recusa definitiva (4xx de negócio): repetir não muda o resultado. */
  record Rejeitado(String motivo) implements Resposta {}

  /**
   * Não houve ativação e vale tentar de novo (5xx, 429, falha de conexão, circuito aberto). {@code
   * aguardar} vem do Retry-After quando o provedor o informa; pode ser nulo.
   */
  record Indisponivel(String motivo, Duration aguardar) implements Resposta {}

  /** Timeout de leitura: o provedor pode ter ativado. Seguro repetir com a mesma chave. */
  record SemConfirmacao(String motivo) implements Resposta {}
}
