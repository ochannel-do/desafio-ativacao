package com.exemplo.ativacao.core.application.port.out;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
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

  Optional<SolicitacaoAtivacao> porExternalId(String externalId);

  Fatia listar(Filtro filtro);

  /**
   * Grava o novo estado se ninguém alterou a solicitação desde a leitura.
   *
   * @throws ConflitoDeVersaoException quando a versão lida está desatualizada
   */
  void atualizar(SolicitacaoAtivacao solicitacao);

  record Fatia(List<SolicitacaoAtivacao> itens, long total) {}

  final class ConflitoDeVersaoException extends RuntimeException {
    public ConflitoDeVersaoException(UUID id) {
      super("Solicitação " + id + " alterada concorrentemente");
    }
  }
}
