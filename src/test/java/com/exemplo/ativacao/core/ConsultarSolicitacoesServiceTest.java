package com.exemplo.ativacao.core;

import static org.assertj.core.api.Assertions.*;

import com.exemplo.ativacao.core.application.port.in.ListarSolicitacoesUseCase.Filtro;
import com.exemplo.ativacao.core.application.usecase.ConsultarSolicitacoesService;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.support.RepositorioEmMemoria;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsultarSolicitacoesServiceTest {
  final RepositorioEmMemoria solicitacaoAdapter = new RepositorioEmMemoria();
  final ConsultarSolicitacoesService service = new ConsultarSolicitacoesService(solicitacaoAdapter);
  final SolicitacaoAtivacao deA =
      solicitacaoAdapter.salvar(
          SolicitacaoAtivacao.nova(
              UUID.randomUUID(), "sistema-a", "EXT-1", "C", "P", null, Instant.EPOCH));

  @Test
  void donoConsultaEOutroConsumidorNaoDescobreQueExiste() {
    assertThat(service.porId(deA.id(), "sistema-a")).isPresent();
    assertThat(service.porId(deA.id(), "sistema-b")).isEmpty();
    assertThat(service.porId(UUID.randomUUID(), "sistema-a")).isEmpty();
  }

  @Test
  void listagemSoTrazOsDoConsumidor() {
    assertThat(service.listar(new Filtro("sistema-a", null, null, 0, 10)).total()).isEqualTo(1);
    assertThat(service.listar(new Filtro("sistema-b", null, null, 0, 10)).itens()).isEmpty();
    assertThatThrownBy(() -> new Filtro(null, null, null, 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Filtro("a", null, null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Filtro("a", null, null, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
