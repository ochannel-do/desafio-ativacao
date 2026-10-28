# ADR 001 — Base do projeto

Status: aceita. Data: 2026-10-28.

- Ponto de partida: base própria de backend (monólito modular hexagonal com ArchUnit, Flyway, Testcontainers, OpenTelemetry e CI), reduzida a um único módulo de ativação.
- Versões: Java 21 (exigido pelo desafio, LTS) e Spring Boot 3.5, que é a linha 3.x usada pela vaga. A base original usava Java 25 e Boot 4.1; a troca exigiu ajustar starters (web, Flyway, tracing) e Testcontainers.
- Core puro: domínio e casos de uso em Java sem framework; Spring, JPA e HTTP são detalhes de infraestrutura.
- PostgreSQL e Testcontainers: constraints, locking e concorrência testados no banco real, sem H2.
- Observabilidade desde o início: logs JSON com correlationId, Actuator com liveness/readiness, tracing OTLP para Jaeger.
- Ambiente local: Docker Compose com aplicação, PostgreSQL, mock do provedor (WireMock) e Jaeger.
- Imagem: build multi-estágio, camadas do Spring Boot extraídas, JRE enxuto, usuário não-root, memória da JVM relativa ao contêiner, desligamento gracioso.
- Testes de integração usam o perfil `test` (JWT HMAC, agendador desligado salvo quando o teste liga).
- Fora de escopo: Kubernetes e GitFlow da base original. O desafio pede só Dockerfile; como executaria em produção fica descrito no README.
