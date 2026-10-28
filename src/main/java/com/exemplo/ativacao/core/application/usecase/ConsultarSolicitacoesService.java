package com.exemplo.ativacao.core.application.usecase;

import com.exemplo.ativacao.core.application.port.in.ConsultarSolicitacaoUseCase;
import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.in.SolicitacaoView;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import java.util.Optional;
import java.util.UUID;

public final class ConsultarSolicitacoesService
    implements ConsultarSolicitacaoUseCase, ListarSolicitacoesUseCase {
  private final SolicitacaoRepository solicitacaoAdapter;

  public ConsultarSolicitacoesService(SolicitacaoRepository solicitacaoAdapter) {
    this.solicitacaoAdapter = solicitacaoAdapter;
  }

  public Optional<SolicitacaoView> porId(UUID id, String consumidor) {
    return solicitacaoAdapter
        .porId(id)
        .filter(s -> s.pertenceA(consumidor))
        .map(SolicitacaoView::de);
  }

  public Pagina listar(Filtro filtro) {
    var fatia = solicitacaoAdapter.listar(filtro);
    return new Pagina(
        fatia.itens().stream().map(SolicitacaoView::de).toList(),
        filtro.pagina(),
        filtro.tamanho(),
        fatia.total());
  }
}
