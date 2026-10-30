package com.exemplo.ativacao.core.application.port.out;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import java.time.Instant;
import java.util.UUID;

public interface Inbox {
  boolean reservar(Mensagem mensagem, UUID posse, Instant agora);

  void concluir(Mensagem mensagem, UUID posse, Instant agora);

  void recuperar(UUID requestId, long versaoEmProcessamento, Instant agora);

  final class MensagemEmProcessamentoException extends RuntimeException {
    public MensagemEmProcessamentoException() {
      super("Mensagem ainda em processamento; aguardar reconciliação");
    }
  }
}
