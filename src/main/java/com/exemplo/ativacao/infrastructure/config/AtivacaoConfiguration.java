package com.exemplo.ativacao.infrastructure.config;

import com.exemplo.ativacao.core.application.port.in.*;
import com.exemplo.ativacao.core.application.port.out.*;
import com.exemplo.ativacao.core.application.usecase.*;
import com.exemplo.ativacao.infrastructure.integration.*;
import com.exemplo.ativacao.infrastructure.observability.RegistrarSolicitacaoObservado;
import com.exemplo.ativacao.infrastructure.persistence.*;
import com.exemplo.ativacao.infrastructure.scheduling.*;
import com.exemplo.ativacao.infrastructure.transaction.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({ProvedorProperties.class, ProcessamentoProperties.class})
public class AtivacaoConfiguration {
  /** UTC com precisão de microssegundos, a mesma do timestamptz do PostgreSQL. */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  @Bean
  SolicitacaoRepository solicitacaoRepository(SolicitacaoJpaRepository repository) {
    return new SolicitacaoRepositoryAdapter(repository);
  }

  @Bean
  Transacao transacao(PlatformTransactionManager manager) {
    return new TransacaoSpring(new TransactionTemplate(manager));
  }

  @Bean
  RegistrarSolicitacaoUseCase registrarSolicitacaoUseCase(
      SolicitacaoRepository repository,
      Clock clock,
      PlatformTransactionManager manager,
      ObservationRegistry registry) {
    var service = new RegistrarSolicitacaoService(repository, clock, UUID::randomUUID);
    return new RegistrarSolicitacaoObservado(
        new RegistrarSolicitacaoTransacional(service, new TransactionTemplate(manager)), registry);
  }

  @Bean
  ConsultarSolicitacoesService consultarSolicitacoesService(SolicitacaoRepository repository) {
    return new ConsultarSolicitacoesService(repository);
  }

  @Bean
  ProvedorAtivacao provedorAtivacao(
      ProvedorProperties props,
      RestClient.Builder builder,
      CircuitBreakerRegistry circuitBreakers,
      Clock clock,
      MeterRegistry metricas) {
    return new ProvedorHttpAdapter(
        ProvedorHttpAdapter.cliente(builder, props),
        circuitBreakers.circuitBreaker("provedor"),
        clock,
        metricas);
  }

  @Bean
  PoliticaDeRetentativa politicaDeRetentativa(ProcessamentoProperties props) {
    return new PoliticaDeRetentativa(
        props.maximoTentativas(),
        props.backoffBase(),
        props.backoffTeto(),
        () -> ThreadLocalRandom.current().nextDouble());
  }

  @Bean
  ProcessarSolicitacoesUseCase processarSolicitacoesUseCase(
      SolicitacaoRepository repository,
      ProvedorAtivacao provedor,
      Transacao transacao,
      PoliticaDeRetentativa politica,
      Clock clock,
      ProcessamentoProperties props) {
    return new ProcessarSolicitacoesService(
        repository, provedor, transacao, politica, clock, props.tamanhoLote());
  }

  @Bean
  ReconciliarSolicitacoesUseCase reconciliarSolicitacoesUseCase(
      SolicitacaoRepository repository,
      Transacao transacao,
      PoliticaDeRetentativa politica,
      Clock clock,
      ProcessamentoProperties props) {
    return new ReconciliarSolicitacoesService(
        repository, transacao, politica, clock, props.prazoProcessamento(), props.tamanhoLote());
  }

  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(
      name = "ativacao.processamento.habilitado",
      havingValue = "true",
      matchIfMissing = true)
  static class Agendamento {
    @Bean
    AtivacaoAgendador ativacaoAgendador(
        ProcessarSolicitacoesUseCase processar, ReconciliarSolicitacoesUseCase reconciliar) {
      return new AtivacaoAgendador(processar, reconciliar);
    }
  }
}
