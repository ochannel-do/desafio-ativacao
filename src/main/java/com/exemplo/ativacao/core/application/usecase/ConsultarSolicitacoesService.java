package com.exemplo.ativacao.core.application.usecase;

import com.exemplo.ativacao.core.application.port.in.ConsultarSolicitacaoUseCase;
import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase;
import com.exemplo.ativacao.core.application.port.in.SolicitacaoView;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import java.util.Optional;
import java.util.UUID;

public final class ConsultarSolicitacoesService
    implements ConsultarSolicitacaoUseCase, ListarSolicitacoesUseCase {
  private final SolicitacaoRepository repository;

  public ConsultarSolicitacoesService(SolicitacaoRepository repository) {
    this.repository = repository;
  }

  public Optional<SolicitacaoView> porId(UUID id, String consumidor) {
    return repository.porId(id).filter(s -> s.pertenceA(consumidor)).map(SolicitacaoView::de);
  }

  public Pagina listar(Filtro filtro) {
    var fatia = repository.listar(filtro);
    return new Pagina(
        fatia.itens().stream().map(SolicitacaoView::de).toList(),
        filtro.pagina(),
        filtro.tamanho(),
        fatia.total());
  }
}
