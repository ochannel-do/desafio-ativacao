package com.exemplo.ativacao.core.application.port.in;

import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import java.util.List;

public interface ListarSolicitacoesUseCase {
  int TAMANHO_MAXIMO = 100;

  Pagina listar(Filtro filtro);

  /** {@code consumidor} é obrigatório; status e customerId nulos significam sem filtro. */
  record Filtro(
      String consumidor, StatusSolicitacao status, String customerId, int pagina, int tamanho) {
    public Filtro {
      if (consumidor == null) throw new IllegalArgumentException("consumidor obrigatório");
      if (pagina < 0) throw new IllegalArgumentException("pagina negativa");
      if (tamanho < 1 || tamanho > TAMANHO_MAXIMO)
        throw new IllegalArgumentException("tamanho fora de 1.." + TAMANHO_MAXIMO);
    }
  }

  record Pagina(List<SolicitacaoView> itens, int pagina, int tamanho, long total) {
    public Pagina {
      itens = List.copyOf(itens);
    }
  }
}
