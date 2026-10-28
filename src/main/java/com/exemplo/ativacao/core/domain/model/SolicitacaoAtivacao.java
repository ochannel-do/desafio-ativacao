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
  private static final int TAMANHO_MAXIMO_ERRO = 255;
  private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final UUID id;
  private final String consumidor;
  private final String externalId;
  private final String customerId;
  private final String productCode;
  private final String correlationId;
  private final Instant createdAt;
  private final long versao;
  private StatusSolicitacao status;
  private int tentativas;
  private Instant proximaTentativaEm;
  private String ultimoErro;
  private Instant updatedAt;

  private SolicitacaoAtivacao(Dados d) {
    this.id = Objects.requireNonNull(d.id(), "id");
    this.consumidor = Objects.requireNonNull(d.consumidor(), "consumidor");
    this.externalId = identificador("externalId", d.externalId());
    this.customerId = identificador("customerId", d.customerId());
    this.productCode = identificador("productCode", d.productCode());
    this.correlationId = d.correlationId();
    this.status = Objects.requireNonNull(d.status(), "status");
    if (d.tentativas() < 0) throw new IllegalArgumentException("tentativas negativas");
    this.tentativas = d.tentativas();
    this.proximaTentativaEm = Objects.requireNonNull(d.proximaTentativaEm(), "proximaTentativaEm");
    this.ultimoErro = d.ultimoErro();
    this.createdAt = Objects.requireNonNull(d.createdAt(), "createdAt");
    this.updatedAt = Objects.requireNonNull(d.updatedAt(), "updatedAt");
    this.versao = d.versao();
  }

  /** Estado persistido, usado só para reconstituir o agregado. */
  public record Dados(
      UUID id,
      String consumidor,
      String externalId,
      String customerId,
      String productCode,
      String correlationId,
      StatusSolicitacao status,
      int tentativas,
      Instant proximaTentativaEm,
      String ultimoErro,
      Instant createdAt,
      Instant updatedAt,
      long versao) {}

  /** {@code consumidor} é o sistema cliente autenticado que criou a solicitação. */
  public static SolicitacaoAtivacao nova(
      UUID id,
      String consumidor,
      String externalId,
      String customerId,
      String productCode,
      String correlationId,
      Instant agora) {
    return new SolicitacaoAtivacao(
        new Dados(
            id,
            consumidor,
            externalId,
            customerId,
            productCode,
            correlationId,
            PENDING,
            0,
            agora,
            null,
            agora,
            agora,
            0));
  }

  public static SolicitacaoAtivacao reconstituir(Dados dados) {
    return new SolicitacaoAtivacao(dados);
  }

  /** PENDING -> PROCESSING: uma tentativa de ativação começa e é contada. */
  public void iniciarProcessamento(Instant agora) {
    mover(PENDING, PROCESSING, agora);
    tentativas++;
  }

  /** PROCESSING -> COMPLETED: o provedor confirmou a ativação. */
  public void concluir(Instant agora) {
    mover(PROCESSING, COMPLETED, agora);
    ultimoErro = null;
  }

  /** PROCESSING -> FAILED: falha definitiva, não haverá nova tentativa. */
  public void falhar(Instant agora, String motivo) {
    String erro = motivo(motivo);
    mover(PROCESSING, FAILED, agora);
    ultimoErro = erro;
  }

  /** PROCESSING -> PENDING: falha temporária, a solicitação volta para a fila. */
  public void devolverParaFila(Instant agora, Instant proximaTentativa, String motivo) {
    Objects.requireNonNull(proximaTentativa, "proximaTentativa");
    String erro = motivo(motivo);
    mover(PROCESSING, PENDING, agora);
    proximaTentativaEm = proximaTentativa;
    ultimoErro = erro;
  }

  /** Retry legítimo: mesmo consumidor e mesmo conteúdo. */
  public boolean mesmoConteudo(String consumidor, String customerId, String productCode) {
    return this.consumidor.equals(consumidor)
        && this.customerId.equals(customerId)
        && this.productCode.equals(productCode);
  }

  public boolean pertenceA(String consumidor) {
    return this.consumidor.equals(consumidor);
  }

  public Dados dados() {
    return new Dados(
        id,
        consumidor,
        externalId,
        customerId,
        productCode,
        correlationId,
        status,
        tentativas,
        proximaTentativaEm,
        ultimoErro,
        createdAt,
        updatedAt,
        versao);
  }

  private void mover(StatusSolicitacao esperado, StatusSolicitacao destino, Instant agora) {
    if (status != esperado) throw new TransicaoInvalidaException(status, destino);
    status = destino;
    updatedAt = Objects.requireNonNull(agora, "agora");
  }

  private static String motivo(String motivo) {
    if (motivo == null || motivo.isBlank()) throw new IllegalArgumentException("motivo vazio");
    return motivo.length() > TAMANHO_MAXIMO_ERRO
        ? motivo.substring(0, TAMANHO_MAXIMO_ERRO)
        : motivo;
  }

  private static String identificador(String campo, String valor) {
    if (valor == null || valor.length() > TAMANHO_MAXIMO || !IDENTIFICADOR.matcher(valor).matches())
      throw new IllegalArgumentException(campo + " inválido");
    return valor;
  }

  public UUID id() {
    return id;
  }

  public String consumidor() {
    return consumidor;
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

  public String correlationId() {
    return correlationId;
  }

  public StatusSolicitacao status() {
    return status;
  }

  public int tentativas() {
    return tentativas;
  }

  public Instant proximaTentativaEm() {
    return proximaTentativaEm;
  }

  public String ultimoErro() {
    return ultimoErro;
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
