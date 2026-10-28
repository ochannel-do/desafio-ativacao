package com.exemplo.ativacao.core.domain.model;

import static com.exemplo.ativacao.core.domain.model.StatusSolicitacao.*;

import com.exemplo.ativacao.core.domain.exception.TransicaoInvalidaException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agregado da solicitação. O status só muda pelos métodos de transição; {@code versao} é o token de
 * concorrência otimista devolvido pela persistência e não tem significado de negócio.
 */
public final class SolicitacaoAtivacao {
  public static final int TAMANHO_MAXIMO = 64;
  private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final UUID id;
  private final String externalId;
  private final String customerId;
  private final String productCode;
  private final Instant createdAt;
  private final long versao;
  private StatusSolicitacao status;
  private int tentativas;
  private Instant updatedAt;

  private SolicitacaoAtivacao(
      UUID id,
      String externalId,
      String customerId,
      String productCode,
      StatusSolicitacao status,
      int tentativas,
      Instant createdAt,
      Instant updatedAt,
      long versao) {
    this.id = Objects.requireNonNull(id, "id");
    this.externalId = identificador("externalId", externalId);
    this.customerId = identificador("customerId", customerId);
    this.productCode = identificador("productCode", productCode);
    this.status = Objects.requireNonNull(status, "status");
    if (tentativas < 0) throw new IllegalArgumentException("tentativas negativas");
    this.tentativas = tentativas;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.versao = versao;
  }

  public static SolicitacaoAtivacao nova(
      UUID id, String externalId, String customerId, String productCode, Instant agora) {
    return new SolicitacaoAtivacao(
        id, externalId, customerId, productCode, PENDING, 0, agora, agora, 0);
  }

  public static SolicitacaoAtivacao reconstituir(
      UUID id,
      String externalId,
      String customerId,
      String productCode,
      StatusSolicitacao status,
      int tentativas,
      Instant createdAt,
      Instant updatedAt,
      long versao) {
    return new SolicitacaoAtivacao(
        id, externalId, customerId, productCode, status, tentativas, createdAt, updatedAt, versao);
  }

  /** PENDING -> PROCESSING: uma tentativa de ativação começa. */
  public void iniciarProcessamento(Instant agora) {
    mover(PENDING, PROCESSING, agora);
    tentativas++;
  }

  /** PROCESSING -> COMPLETED: o provedor confirmou a ativação. */
  public void concluir(Instant agora) {
    mover(PROCESSING, COMPLETED, agora);
  }

  /** PROCESSING -> FAILED: falha definitiva, não haverá nova tentativa. */
  public void falhar(Instant agora) {
    mover(PROCESSING, FAILED, agora);
  }

  /** PROCESSING -> PENDING: falha temporária, a solicitação volta para a fila. */
  public void devolverParaFila(Instant agora) {
    mover(PROCESSING, PENDING, agora);
  }

  public boolean mesmoConteudo(String customerId, String productCode) {
    return this.customerId.equals(customerId) && this.productCode.equals(productCode);
  }

  private void mover(StatusSolicitacao esperado, StatusSolicitacao destino, Instant agora) {
    if (status != esperado) throw new TransicaoInvalidaException(status, destino);
    status = destino;
    updatedAt = Objects.requireNonNull(agora, "agora");
  }

  private static String identificador(String campo, String valor) {
    if (valor == null || valor.length() > TAMANHO_MAXIMO || !IDENTIFICADOR.matcher(valor).matches())
      throw new IllegalArgumentException(campo + " inválido");
    return valor;
  }

  public UUID id() {
    return id;
  }

  public String externalId() {
    return externalId;
  }

  public String customerId() {
    return customerId;
  }

  public String productCode() {
    return productCode;
  }

  public StatusSolicitacao status() {
    return status;
  }

  public int tentativas() {
    return tentativas;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public long versao() {
    return versao;
  }
}
