package com.exemplo.ativacao.core.application.port.out;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SolicitacaoRepository {
  /**
   * Insere de forma atômica. Retorna {@code false} quando o externalId já existe, inclusive se
   * outra transação o inseriu ao mesmo tempo.
   */
  boolean inserirSeAusente(SolicitacaoAtivacao solicitacao);

  Optional<SolicitacaoAtivacao> porId(UUID id);

  /** Reserva uma solicitação do consumidor dentro da transação do chamador. */
  Optional<SolicitacaoAtivacao> bloquearPorId(UUID id, String consumidor);

  Optional<SolicitacaoAtivacao> porExternalId(String externalId);

  Fatia listar(Filtro filtro);

  /**
   * Pendentes cuja próxima tentativa já venceu, bloqueadas até o fim da transação atual. Linhas
   * bloqueadas por outra instância são puladas, então instâncias paralelas nunca pegam a mesma.
   */
  List<SolicitacaoAtivacao> bloquearPendentes(Instant agora, int limite);

  /** Em PROCESSING sem atualização desde {@code limite}, bloqueadas como em bloquearPendentes. */
  List<SolicitacaoAtivacao> bloquearPresasEmProcessamento(Instant limite, int quantidade);

  /**
   * Grava o novo estado se ninguém alterou a solicitação desde a leitura.
   *
   * @return o estado gravado, com a nova versão
   * @throws ConflitoDeVersaoException quando a versão lida está desatualizada
   */
  SolicitacaoAtivacao atualizar(SolicitacaoAtivacao solicitacao);

  record Fatia(List<SolicitacaoAtivacao> itens, long total) {}

  final class ConflitoDeVersaoException extends RuntimeException {
    public ConflitoDeVersaoException(UUID id) {
      super("Solicitação " + id + " alterada concorrentemente");
    }
  }
}
