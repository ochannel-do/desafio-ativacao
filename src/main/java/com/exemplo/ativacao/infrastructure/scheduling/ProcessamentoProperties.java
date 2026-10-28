package com.exemplo.ativacao.infrastructure.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code prazoProcessamento} precisa ser bem maior que connect + read timeout do provedor, senão a
 * reconciliação devolveria para a fila uma chamada ainda em andamento.
 */
@ConfigurationProperties("ativacao.processamento")
public record ProcessamentoProperties(
    @DefaultValue("true") boolean habilitado,
    @DefaultValue("1s") Duration intervalo,
    @DefaultValue("20") int tamanhoLote,
    @DefaultValue("5") int maximoTentativas,
    @DefaultValue("2s") Duration backoffBase,
    @DefaultValue("5m") Duration backoffTeto,
    @DefaultValue("60s") Duration prazoProcessamento,
    @DefaultValue("30s") Duration intervaloReconciliacao) {}
