package com.exemplo.ativacao.infrastructure.web;

import com.exemplo.ativacao.core.application.port.in.*;
import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.*;
import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import com.exemplo.ativacao.infrastructure.web.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/requests")
public class SolicitacaoController {
  private final RegistrarSolicitacaoUseCase registrar;
  private final ConsultarSolicitacaoUseCase consultar;
  private final ListarSolicitacoesUseCase listar;

  public SolicitacaoController(
      RegistrarSolicitacaoUseCase registrar,
      ConsultarSolicitacaoUseCase consultar,
      ListarSolicitacoesUseCase listar) {
    this.registrar = registrar;
    this.consultar = consultar;
    this.listar = listar;
  }

  /** 201 na criação; 200 com o registro original num retry idêntico; 409 se o conteúdo diverge. */
  @PostMapping
  public ResponseEntity<SolicitacaoResponse> criar(
      @Valid @RequestBody CriarSolicitacaoRequest request) {
    return switch (registrar.executar(request.comando())) {
      case Criada(var s) ->
          ResponseEntity.created(
                  ServletUriComponentsBuilder.fromCurrentRequest()
                      .path("/{id}")
                      .buildAndExpand(s.id())
                      .toUri())
              .body(SolicitacaoResponse.de(s));
      case Repetida(var s) -> ResponseEntity.ok(SolicitacaoResponse.de(s));
      case Conflitante(var externalId) -> throw new ExternalIdConflitanteException(externalId);
    };
  }

  @GetMapping("/{id}")
  public SolicitacaoResponse consultar(@PathVariable UUID id) {
    return consultar
        .porId(id)
        .map(SolicitacaoResponse::de)
        .orElseThrow(() -> new SolicitacaoNaoEncontradaException(id));
  }

  @GetMapping
  public PaginaResponse listar(
      @RequestParam(required = false) StatusSolicitacao status,
      @RequestParam(required = false) @Size(max = 64) String customerId,
      @RequestParam(defaultValue = "0") @PositiveOrZero int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(ListarSolicitacoesUseCase.TAMANHO_MAXIMO)
          int size) {
    return PaginaResponse.de(
        listar.listar(new ListarSolicitacoesUseCase.Filtro(status, customerId, page, size)));
  }
}
