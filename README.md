# Serviço de ativação de produtos

Desafio técnico Java Pleno/Sênior. Registra solicitações de ativação de produtos, consulta status e integra com um provedor externo.

> Em construção. O README final terá as seções exigidas: como executar, decisões técnicas, cenário de análise, produção, pontos pendentes, melhorias futuras e uso de IA.

## Executar

Requisitos: JDK 21 e Docker.

```sh
./mvnw verify                          # testes unitários e de integração (Testcontainers)
docker compose up -d postgres provider-mock jaeger
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
docker compose --profile app up --build -d   # tudo em contêiner
```

Saúde: `GET /actuator/health/liveness` e `GET /actuator/health/readiness`. Traces: http://localhost:16686.

Decisões: [docs/adr](docs/adr).
