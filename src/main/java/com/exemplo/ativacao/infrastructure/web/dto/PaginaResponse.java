package com.exemplo.ativacao.infrastructure.web.dto;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase;
import java.util.List;

public record PaginaResponse(
    List<SolicitacaoResponse> items, int page, int size, long totalElements, long totalPages) {
  public static PaginaResponse de(ListarSolicitacoesUseCase.Pagina p) {
    return new PaginaResponse(
        p.itens().stream().map(SolicitacaoResponse::de).toList(),
        p.pagina(),
        p.tamanho(),
        p.total(),
        (p.total() + p.tamanho() - 1) / p.tamanho());
  }
}
