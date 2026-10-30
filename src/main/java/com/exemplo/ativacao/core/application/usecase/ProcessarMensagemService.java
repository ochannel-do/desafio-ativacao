package com.exemplo.ativacao.core.application.usecase;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;

import com.exemplo.ativacao.core.application.port.in.ProcessarMensagemUseCase;
import com.exemplo.ativacao.core.application.port.out.*;
import com.exemplo.ativacao.core.application.port.out.ProvedorAtivacao.*;
import com.exemplo.ativacao.core.domain.model.SolicitacaoAtivacao;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Reserva e desfecho são transações distintas. Nenhuma transação envolve o provedor HTTP. */
public final class ProcessarMensagemService implements ProcessarMensagemUseCase {
  private final SolicitacaoRepository repository;
  private final Inbox inbox;
  private final ProvedorAtivacao provedor;
  private final Transacao transacao;
  private final PoliticaDeRetentativa politica;
  private final Clock clock;

  public ProcessarMensagemService(
      SolicitacaoRepository repository,
      Inbox inbox,
      ProvedorAtivacao provedor,
      Transacao transacao,
      PoliticaDeRetentativa politica,
      Clock clock) {
    this.repository = repository;
    this.inbox = inbox;
    this.provedor = provedor;
    this.transacao = transacao;
    this.politica = politica;
    this.clock = clock;
  }

  public void executar(Mensagem m) {
    var posse = UUID.randomUUID();
    var reservada = transacao.executar(() -> reservar(m, posse));
    if (reservada == null) return;
    var resposta =
        provedor.ativar(
            new Pedido(
                reservada.id(),
                reservada.externalId(),
                reservada.customerId(),
                reservada.productCode(),
                reservada.correlationId()));
    transacao.executar(
        () -> {
          var atual = repository.bloquearPorId(m.requestId(), m.clientId()).orElseThrow();
          // A reconciliação encerrou a inbox antiga e persistiu uma nova tentativa.
          if (atual.versao() != reservada.versao() || atual.status() != PROCESSING) return null;
          var agora = clock.instant();
          switch (resposta) {
            case Ativado a -> atual.concluir(agora);
            case Rejeitado(var motivo) -> atual.falhar(agora, motivo);
            case Indisponivel(var motivo, var aguardar) -> retentar(atual, motivo, aguardar);
            case SemConfirmacao(var motivo) -> retentar(atual, motivo, null);
          }
          repository.atualizar(atual);
          inbox.concluir(m, posse, agora);
          return null;
        });
  }

  private SolicitacaoAtivacao reservar(Mensagem m, UUID posse) {
    // A mesma ordem de locks da reconciliação: solicitação antes de inbox.
    var atual = repository.bloquearPorId(m.requestId(), m.clientId());
    var agora = clock.instant();
    if (!inbox.reservar(m, posse, agora)) return null;
    if (atual.isEmpty()
        || atual.get().status() != PENDING
        || atual.get().versao() != m.requestVersion()) {
      inbox.concluir(m, posse, agora);
      return null;
    }
    var s = atual.get();
    if (s.proximaTentativaEm().isAfter(agora))
      throw new IllegalStateException("Evento recebido antes da próxima tentativa");
    s.iniciarProcessamento(agora);
    return repository.atualizar(s);
  }

  private void retentar(SolicitacaoAtivacao s, String motivo, Duration aguardar) {
    var agora = clock.instant();
    if (politica.esgotou(s.tentativas())) s.falhar(agora, "Tentativas esgotadas: " + motivo);
    else
      s.devolverParaFila(agora, politica.proximaTentativa(s.tentativas(), agora, aguardar), motivo);
  }
}
