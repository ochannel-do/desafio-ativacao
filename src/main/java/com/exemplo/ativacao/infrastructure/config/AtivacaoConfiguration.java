package com.exemplo.ativacao.infrastructure.config;

import com.exemplo.ativacao.core.application.port.in.RegistrarSolicitacaoUseCase;
import com.exemplo.ativacao.core.application.port.out.SolicitacaoRepository;
import com.exemplo.ativacao.core.application.usecase.ConsultarSolicitacoesService;
import com.exemplo.ativacao.core.application.usecase.RegistrarSolicitacaoService;
import com.exemplo.ativacao.infrastructure.observability.RegistrarSolicitacaoObservado;
import com.exemplo.ativacao.infrastructure.persistence.*;
import com.exemplo.ativacao.infrastructure.transaction.RegistrarSolicitacaoTransacional;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
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
}
