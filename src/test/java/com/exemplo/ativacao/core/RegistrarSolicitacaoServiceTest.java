package com.exemplo.ativacao.core;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase.*;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.application.usecase.RegistrarSolicitacaoService;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import com.exemplo.ativacao.core.domain.model.StatusSolicitacao;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrarSolicitacaoServiceTest {
  final Instant agora = Instant.parse("2026-10-26T14:03:11Z");
  final UUID id = UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90");
  final SolicitacaoRepository repository = mock(SolicitacaoRepository.class);
  final RegistrarSolicitacaoService service =
      new RegistrarSolicitacaoService(repository, Clock.fixed(agora, ZoneOffset.UTC), () -> id);
  final Comando comando = new Comando("EXT-12345", "CUST-10001", "INTERNET-500", "trace-1");

  @Test
  void criaQuandoExternalIdEhNovo() {
    when(repository.inserirSeAusente(any())).thenReturn(true);
    var resultado = service.executar(comando);
    assertThat(resultado)
        .isInstanceOfSatisfying(
            Criada.class,
            c -> {
              assertThat(c.solicitacao().id()).isEqualTo(id);
              assertThat(c.solicitacao().status()).isEqualTo(StatusSolicitacao.PENDING);
              assertThat(c.solicitacao().createdAt()).isEqualTo(agora);
              assertThat(c.solicitacao().tentativas()).isZero();
            });
    verify(repository, never()).porExternalId(any());
  }

  @Test
  void retryComMesmoConteudoDevolveRegistroOriginal() {
    var original =
        SolicitacaoAtivacao.nova(
            UUID.randomUUID(),
            "EXT-12345",
            "CUST-10001",
            "INTERNET-500",
            null,
            agora.minusSeconds(60));
    when(repository.inserirSeAusente(any())).thenReturn(false);
    when(repository.porExternalId("EXT-12345")).thenReturn(Optional.of(original));
    assertThat(service.executar(comando))
        .isInstanceOfSatisfying(
            Repetida.class, r -> assertThat(r.solicitacao().id()).isEqualTo(original.id()));
  }

  @Test
  void conteudoDiferenteEhConflito() {
    var original =
        SolicitacaoAtivacao.nova(
            UUID.randomUUID(), "EXT-12345", "CUST-10001", "TV-100", null, agora);
    when(repository.inserirSeAusente(any())).thenReturn(false);
    when(repository.porExternalId("EXT-12345")).thenReturn(Optional.of(original));
    assertThat(service.executar(comando)).isEqualTo(new Conflitante("EXT-12345"));
  }

  @Test
  void dadosInvalidosNaoChegamAoRepositorio() {
    assertThatThrownBy(() -> service.executar(new Comando("", "C", "P", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.executar(null)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }
}
