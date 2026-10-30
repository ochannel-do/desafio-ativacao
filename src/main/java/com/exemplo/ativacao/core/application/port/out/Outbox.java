package com.exemplo.ativacao.core.application.port.out;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase.Mensagem;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Outbox {
  void agendar(SolicitacaoAtivacao solicitacao);

  List<Publicacao> reservar(Instant agora, Instant expiraEm, int limite);

  void confirmar(Publicacao publicacao, Instant agora);

  void liberar(Publicacao publicacao, Instant tentarEm);

  record Publicacao(Mensagem mensagem, UUID posse) {}
}
