# Serviço de ativação de produtos

Registra solicitações de ativação de produtos de clientes, consulta o status e ativa cada produto em um provedor externo, com proteção contra duplicidade, processamento assíncrono resiliente e API protegida por OAuth 2.0/JWT.

Java 21 · Spring Boot 3.5 · Spring MVC · Spring Data JPA/Hibernate · PostgreSQL · Flyway · Resilience4j · Spring Security (Resource Server) · Micrometer/OpenTelemetry · JUnit 5, Mockito, Testcontainers, WireMock, ArchUnit · Docker.

## Sumário

- [Como executar](#como-executar)
- [API](#api)
- [Arquitetura](#arquitetura)
- [Decisões técnicas](#decisões-técnicas)
- [Estratégia de testes](#estratégia-de-testes)
- [Cenário de análise](#cenário-de-análise)
- [Produção](#produção)
- [Microsserviços e cloud](#microsserviços-e-cloud)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

## Como executar

Requisitos: JDK 21 e Docker. O Maven vem pelo wrapper.

```sh
./mvnw verify            # formatação, testes unitários e de integração (PostgreSQL real via Testcontainers)
```

Tudo em contêiner (aplicação, PostgreSQL, mock do provedor e Jaeger):

```sh
docker compose --profile app up --build -d
```

Ou só as dependências, com a aplicação na IDE ou no terminal:

```sh
docker compose up -d postgres provider-mock jaeger
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Gere um token local (o perfil `local` aceita JWT HS256 com uma chave de desenvolvimento, sem servidor de autorização) e chame a API:

```sh
TOKEN=$(scripts/token-local.sh sistema-a "activation:write activation:read")

curl -i http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
```

O mock do provedor (WireMock, `wiremock/mappings`) responde conforme o `productCode`: `INVALID-*` devolve 400, `BUSY-*` devolve 503 com `Retry-After`, `SLOW-*` demora 5 s (timeout) e os demais ativam com sucesso.

| Endereço | Para quê |
| --- | --- |
| `http://localhost:8080/api/v1/requests` | API |
| `http://localhost:8080/actuator/health/liveness` e `/readiness` | Probes |
| `http://localhost:8080/actuator/prometheus` | Métricas |
| `http://localhost:16686` | Traces (Jaeger, serviço `ativacao`) |
| `http://localhost:8089/__admin` | Mock do provedor |

## API

Todas as rotas exigem `Authorization: Bearer <jwt>`. Erros seguem Problem Details (RFC 9457) com `traceId`, que também volta no header `X-Correlation-Id` e aparece nos logs.

| Método e rota | Escopo | Respostas |
| --- | --- | --- |
| `POST /api/v1/requests` | `activation:write` | `201` + `Location` ao criar; `200` com o registro original em retry idêntico; `400` dados inválidos; `409` externalId já usado com outro conteúdo; `413` corpo acima de 16 KB |
| `GET /api/v1/requests/{id}` | `activation:read` | `200`; `404` se não existe ou é de outro consumidor |
| `GET /api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200` paginado (`size` até 100, mais recentes primeiro) |

Validação: `externalId`, `customerId` e `productCode` são obrigatórios, têm até 64 caracteres, começam com letra ou dígito e aceitam letras, dígitos, `.`, `_` e `-`. Datas em UTC, ISO-8601, com precisão de microssegundos.

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "COMPLETED",
  "attempts": 1,
  "lastError": null,
  "createdAt": "2026-10-26T14:03:11.123456Z",
  "updatedAt": "2026-10-26T14:03:12.004211Z"
}
```

## Arquitetura

Arquitetura hexagonal enxuta, num único módulo. O `core` é Java puro e as regras de dependência são verificadas por ArchUnit.

```
core/domain        SolicitacaoAtivacao (agregado), StatusSolicitacao, TransicaoInvalidaException
core/application   portas de entrada (casos de uso) e de saída (repositório, provedor, transação)
                   serviços: registrar, consultar, processar lote, reconciliar; política de retentativa
infrastructure     web (controller, DTOs, Problem Details, filtros), persistence (JPA), integration
                   (cliente HTTP + circuit breaker), security, scheduling, transaction, observability, config
```

```
POST ──► registrar ──► INSERT ... ON CONFLICT (PENDING) ──► 201
                                    │
            agendador (cada instância) ▼
   tx1: SELECT ... FOR UPDATE SKIP LOCKED → PROCESSING, commit
   sem tx: POST /provider/v1/activate (Idempotency-Key = id)
   tx2: COMPLETED | FAILED | PENDING com próxima tentativa      (@Version protege a gravação)

   reconciliação: PROCESSING há mais de 60 s → PENDING (ou FAILED se esgotou)
```

Estados e transições (no domínio, sem setters):

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲             │
   └─────────────┤ falha temporária (volta para a fila, contador preservado)
                 └──► FAILED (recusa definitiva ou tentativas esgotadas)
```

`COMPLETED` e `FAILED` são finais. Uma falha temporária devolve a solicitação para `PENDING` com `next_attempt_at` calculado pelo backoff e o contador `attempts` preservado; foi a opção escolhida em vez de manter `PROCESSING` porque `PROCESSING` passa a significar "há uma chamada em andamento agora", o que torna a reconciliação simples e sem ambiguidade.

## Decisões técnicas

Os registros completos estão em [docs/adr](docs/adr).

### Duplicidade (item 3) — [ADR 002](docs/adr/002-duplicidade.md)

A garantia está no banco: `unique (external_id)`. A inserção usa `INSERT ... ON CONFLICT (external_id) DO NOTHING`.

Verificar "existe?" e depois inserir não basta: duas requisições simultâneas passam pela consulta antes de qualquer uma inserir, e as duas inserem. Com a constraint, o PostgreSQL faz a segunda transação esperar a primeira; quando ela confirma, o `ON CONFLICT` devolve 0 linhas sem abortar a transação (diferente de capturar a violação de constraint, que no PostgreSQL invalida a transação inteira). Aí o serviço lê o registro existente e decide:

| Cenário | Resposta | Por quê |
| --- | --- | --- |
| Mesmo `externalId`, mesmo conteúdo e mesmo consumidor | `200` com o registro original | É um retry legítimo do sistema de origem. Responder 409 obrigaria o cliente a tratar como erro algo que deu certo. O 200 (em vez de 201) diz que nada novo foi criado. |
| Mesmo `externalId`, conteúdo diferente | `409` "O externalId já foi registrado com customerId ou productCode diferentes." | É um erro do cliente, e devolver o registro antigo como se fosse o pedido mascararia o problema. |
| Mesmo `externalId` vindo de outro consumidor | `409`, sem dados do registro | Não vaza a solicitação de outro sistema (BOLA). |
| Duas requisições idênticas em paralelo | Uma recebe `201`, a outra `200` com o mesmo `id` | Testado com 16 requisições simultâneas contra PostgreSQL real. |

Vale para qualquer número de instâncias, porque a regra está na constraint e não em memória.

### Integração com o provedor (item 5) — [ADR 003](docs/adr/003-integracao-assincrona.md)

**Assíncrono.** O `POST` grava a solicitação como `PENDING` e responde em milissegundos. Se esperasse o provedor (400 ms normalmente, 3 s no cenário de lentidão), a latência e a disponibilidade da API ficariam presas às dele, e threads do Tomcat ficariam bloqueadas justamente quando ele está lento.

**Transação x chamada remota.** Nenhuma chamada HTTP acontece com transação aberta. A linha `PENDING` gravada no mesmo commit do `POST` é a intenção durável de chamar o provedor (o padrão outbox, usando a própria tabela como fila). Se a aplicação cair logo depois do commit, a linha continua lá e qualquer instância a processa. O worker trabalha em três passos: reserva o lote com `FOR UPDATE SKIP LOCKED` e marca `PROCESSING` (commit), chama o provedor sem transação, grava o desfecho em outra transação.

**Timeout é ambíguo.** O provedor pode ter ativado e a resposta se perdeu. Toda chamada envia `Idempotency-Key` igual ao `id` da solicitação, estável entre tentativas, então repetir não ativa duas vezes (desde que o provedor honre a chave, ponto a combinar no contrato com ele).

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
| --- | --- | --- | --- |
| Sucesso (2xx) | `COMPLETED` | Não | Ativado. |
| HTTP 400, 404, 409, 422 e outros 4xx | `FAILED` | Não | Recusa de negócio: repetir o mesmo pedido dá o mesmo resultado. Não conta como falha no circuit breaker. |
| HTTP 401 / 403 | `PENDING` | Sim, com backoff | A credencial é nossa; o problema não é da solicitação e não deve falhá-la de vez. Conta no circuit breaker e precisa de alerta. |
| HTTP 408 | `PENDING` | Sim, com backoff | Timeout do lado do provedor. |
| HTTP 500, 502, 504 | `PENDING` | Sim, com backoff | Falha temporária do provedor. |
| Timeout de leitura | `PENDING` | Sim, com backoff | Pode ter ativado; a chave de idempotência torna a repetição segura. |
| Falha de conexão | `PENDING` | Sim, com backoff | Nada chegou ao provedor. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` | Sim, no horário pedido (segundos ou data HTTP), limitado ao teto | O provedor disse quando voltar; insistir antes só piora. |
| Circuito aberto | `PENDING` | Sim, com backoff | Provedor degradado; a chamada nem é feita. |
| Tentativas esgotadas (5) | `FAILED` | Não | `lastError` registra o último motivo. |

**Timeouts explícitos.** Conexão 1 s e leitura 3 s, configuráveis por `PROVIDER_CONNECT_TIMEOUT` e `PROVIDER_READ_TIMEOUT`.

**Backoff.** Exponencial a partir de 2 s, teto de 5 min, com jitter (espera sorteada entre metade e o valor cheio) para que solicitações e instâncias não voltem todas ao mesmo tempo depois de uma queda.

**Circuit breaker (Resilience4j).** Janela das últimas 20 chamadas, abre com 50% de falhas ou 80% de chamadas acima de 2 s, fica aberto 30 s e testa com 3 chamadas em meia-abertura. Só falhas do provedor contam; uma recusa 4xx é uma resposta válida. Usei apenas o circuit breaker da biblioteca: o retry fica na fila do banco, porque um retry em memória seguraria a thread e se perderia se a instância caísse.

**Reconciliação.** A cada 30 s, solicitações em `PROCESSING` há mais de 60 s (bem acima de conexão + leitura) voltam para `PENDING`, ou viram `FAILED` se esgotaram as tentativas. Isso cobre a instância que caiu entre a chamada e a gravação do desfecho.

**Concorrência na atualização.** `@Version` (lock otimista) na entidade. Se a reconciliação e o worker tentarem mudar a mesma solicitação, o segundo recebe conflito e o estado gravado pelo primeiro prevalece; a próxima tentativa usa a mesma chave de idempotência.

### Segurança (item 7) — [ADR 004](docs/adr/004-seguranca.md)

Implementado como Resource Server do Spring Security.

- **Fluxo:** OAuth 2.0 Client Credentials. A API é consumida por outros sistemas, sem usuário final; cada sistema tem seu `client_id` e segredo (ou, melhor, `private_key_jwt`/mTLS) no servidor de autorização (Keycloak, Entra ID, Cognito, Okta).
- **Validação do JWT:** assinatura com as chaves públicas do JWKS do emissor (`AUTH_JWK_SET_URI`, RS256), emissor (`iss`), audiência (`aud = ativacao-api`) e expiração (`exp`/`nbf`, com folga de 60 s). Token inválido em qualquer ponto dá `401`. O perfil local e os testes usam HS256 com chave simétrica, só para dispensar o servidor de autorização.
- **Autorização por escopo:** `activation:write` cria; `activation:read` consulta e lista. Escopo insuficiente dá `403`. Qualquer rota sem regra é negada.
- **Isolamento entre consumidores (BOLA, OWASP API1):** o `client_id` do token é gravado como dono da solicitação. Consulta e listagem filtram por ele; a solicitação de outro sistema responde `404`, sem revelar que existe.
- **Limites:** corpo acima de 16 KB é recusado com `413` antes de autenticar ou desserializar. Limite de taxa ficaria no API Gateway (ver pendentes).
- **Credencial do provedor externo:** lida de `PROVIDER_API_KEY`, injetada pela plataforma a partir de um cofre (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager ou Vault), nunca no repositório nem em logs (`toString` mascarado). Rotação: o cofre gera a nova credencial, as duas ficam válidas no provedor durante a janela de troca e um rollout dos pods carrega a nova. Se o provedor aceitar OAuth, o ideal é client credentials também nesta direção, com token de vida curta.

### Outras decisões

- **Erros:** `ProblemDetail` com `type`, `title`, `detail`, `instance` e `traceId`; lista `errors` por campo em validação. Exceções padrão do Spring MVC (JSON malformado, 405, 415) passam pela classe base e não viram 500. Nada de stack trace ou mensagem interna na resposta; `500` registra o erro completo no log com o mesmo `traceId`.
- **Resultado selado:** o caso de uso devolve `Criada | Repetida | Conflitante` (sealed interface) e o controller decide com `switch` exaustivo; o worker faz o mesmo com as respostas do provedor. Adicionar um desfecho novo quebra a compilação onde ele precisa ser tratado.
- **Índices** (migração V3): toda listagem filtra pelo consumidor e ordena por `created_at desc`, então os índices são `(client_id, created_at desc, id desc)`, `(client_id, customer_id, created_at desc, id desc)` e `(client_id, status, created_at desc, id desc)`. Cada um atende um filtro sem ordenar em memória. Para o worker e a reconciliação há índices parciais em `next_attempt_at where status = 'PENDING'` e `updated_at where status = 'PROCESSING'`, pequenos porque só cobrem as linhas em trânsito.
- **Migrações versionadas** com Flyway; o Hibernate só valida o schema.

## Estratégia de testes

83 testes, todos executados por `./mvnw verify` (o CI roda o mesmo comando).

| Nível | O quê | Ferramentas |
| --- | --- | --- |
| Unitário, sem Spring | Transições e invariantes do agregado; registro, retry e conflito; tabela de decisão do worker (inclusive "nenhuma chamada HTTP com transação aberta"); reconciliação; backoff, jitter e Retry-After | JUnit 5, AssertJ, Mockito, fake de repositório em memória |
| Fatia web | Contrato HTTP: 201/200/409/400/404/413, Problem Details, validação de parâmetros, 401 sem token, 403 por escopo | `@WebMvcTest`, MockMvc, spring-security-test |
| Integração do cliente HTTP | 2xx, 4xx, 5xx, 401, 429/503 com Retry-After, timeout, conexão derrubada, circuito abrindo e parando de chamar | WireMock |
| Integração com banco real | Unicidade, `ON CONFLICT`, lock otimista, filtros e paginação, `SKIP LOCKED` entre duas transações, reconciliação | Testcontainers (PostgreSQL 17) |
| Ponta a ponta | 16 POSTs simultâneos geram um registro; POST até `COMPLETED`/`FAILED` com o agendador real; 503 → timeout → sucesso com a mesma chave de idempotência; JWT expirado, de outro emissor, de outra audiência ou adulterado; isolamento entre consumidores | `@SpringBootTest`, Testcontainers, WireMock, Awaitility |
| Arquitetura | `core` sem Spring/JPA/HTTP, domínio sem depender de aplicação, web sem acessar persistência, sem ciclos | ArchUnit |

Unitário é tudo que é regra e cabe em memória: roda em milissegundos e cobre as combinações. Integração fica para o que só o ambiente real mostra: constraint, lock, isolamento de transação, serialização HTTP, validação de JWT. Nada usa H2, porque o comportamento de concorrência que importa aqui é o do PostgreSQL.

## Cenário de análise

> API de 300 ms para 2,5 s, CPU 45%, memória 60%, algumas consultas de 50 ms para 1,2 s, serviço externo de 400 ms para 3 s.

**O que investigaria primeiro.** CPU e memória normais dizem que a aplicação não está calculando mais; ela está esperando. Então começo pelo que ela espera: banco e provedor. Abro o trace de uma requisição lenta (Jaeger/Tempo) para ver onde o tempo está, e cruzo com a linha do tempo de deploys, mudanças de configuração e volume de tráfego.

**Aplicação, banco ou integração.** O tracing quebra a latência por span: tempo no controller, em cada query e na chamada HTTP. Em paralelo:

- *Banco:* `pg_stat_statements` para as queries que pioraram, `EXPLAIN (ANALYZE, BUFFERS)` nelas, `pg_stat_activity` para locks e sessões esperando, tamanho das tabelas e bloat, e se o autovacuum está em dia.
- *Pool:* métricas do HikariCP (`hikaricp_connections_pending`, `..._acquire`, `..._usage`). Se há threads esperando conexão, a lentidão "do banco" pode ser fila no pool.
- *Provedor:* `http_client_requests_seconds` por status, `ativacao_provedor_respostas_total` por desfecho e estado do circuit breaker.
- *Aplicação:* threads do Tomcat ocupadas, pausas de GC e thread dump para ver onde estão paradas.

**Logs e métricas úteis.** Os que o serviço já emite: `http_request_completed` com rota, status e duração por `correlationId`; `provider_activation_called` com desfecho; histogramas de `http.server.requests` e `http.client.requests`; métricas do Hikari, JVM e Resilience4j. Faltaria uma métrica de idade da fila (`now() - min(next_attempt_at)` dos `PENDING`).

**Melhorias possíveis.** Para o banco: índice que falta ou que o planner deixou de usar (estatísticas desatualizadas), paginação por cursor no lugar de `OFFSET` alto, `count` caro em páginas profundas. Para o provedor: timeout coerente com o SLA dele, circuit breaker para falhar rápido e bulkhead para limitar chamadas simultâneas. Neste desenho a API já não chama o provedor de forma síncrona, então a lentidão dele aumenta o tempo até `COMPLETED`, não a latência do `POST`.

**Causa comum (hipótese).** Um sintoma alimentando o outro através do pool de conexões. Se a chamada ao provedor acontecesse dentro de uma transação (o erro que este desenho evita), cada requisição seguraria uma conexão por 3 s em vez de 400 ms. O pool esgota, as queries passam a esperar conexão e parecem lentas, mesmo sem mudar o plano de execução; a API inteira fica lenta com CPU baixa. Outra hipótese com a mesma cara é uma causa de infraestrutura compartilhada (rede, NAT ou DNS da zona), que deixaria banco e provedor lentos ao mesmo tempo. Confirmaria com o trace (o tempo das queries está em "esperando conexão" ou em execução?), com `hikaricp_connections_pending` correlacionado à latência do provedor no tempo, e com `pg_stat_activity` mostrando muitas sessões `idle in transaction`.

**Como validar a correção.** Definir antes o critério (p95 e p99 da API de volta ao patamar anterior, taxa de erro, conexões pendentes no pool perto de zero), publicar em canário ou para parte do tráfego, comparar o mesmo painel antes e depois sob carga equivalente, reproduzir o cenário num teste de carga com o provedor simulado a 3 s, e deixar um alerta de SLO para pegar regressão.

## Produção

- **Imagem:** build multi-estágio, camadas do Spring Boot extraídas, JRE enxuto, usuário não-root (10001), `-XX:MaxRAMPercentage=75` para respeitar o limite do contêiner e `ExitOnOutOfMemoryError` para o orquestrador reiniciar em vez de continuar degradado.
- **Orquestração:** Kubernetes (EKS/AKS/GKE) com 2+ réplicas, `liveness` e `readiness` apontando para os endpoints do Actuator, requests/limits de CPU e memória, `PodDisruptionBudget` e HPA por CPU ou latência.
- **Configuração:** tudo por variável de ambiente (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `AUTH_ISSUER`, `AUTH_AUDIENCE`, `AUTH_JWK_SET_URI`, `PROVIDER_BASE_URL`, `PROVIDER_API_KEY`, `PROVIDER_*_TIMEOUT`, `OTLP_ENDPOINT`, `TRACE_SAMPLING`); segredos vindos do cofre do provedor de cloud, nunca do repositório.
- **Desligamento gracioso:** `server.shutdown=graceful` com 20 s; o Kubernetes tira o pod do balanceador pelo readiness, as requisições em curso terminam, e uma solicitação que estivesse em `PROCESSING` é recuperada pela reconciliação.
- **Banco:** PostgreSQL gerenciado (RDS/Cloud SQL/Azure Database) com réplica e backup; Flyway aplica migrações na subida (ou como job separado do pipeline, se o time preferir separar).
- **Observabilidade:** logs JSON no stdout para Loki/CloudWatch/Elastic, métricas Prometheus, traces OTLP para um coletor. Em produção eu exporia o Actuator numa porta de gestão separada (`MANAGEMENT_SERVER_PORT`), acessível só de dentro do cluster.

## Microsserviços e cloud

- **Várias instâncias:** a regra de duplicidade está na constraint do banco e o trabalho da fila é repartido por `FOR UPDATE SKIP LOCKED`, então N réplicas funcionam sem coordenação extra, eleição de líder ou lock distribuído. A reconciliação também usa `SKIP LOCKED`. O estado do circuit breaker é por instância, o que é aceitável: cada réplica abre o próprio circuito ao ver o provedor falhar.
- **Configurações e segredos:** variáveis de ambiente para configuração; segredos no cofre gerenciado, montados via External Secrets/CSI driver, com rotação sem rebuild de imagem.
- **Avisar outros serviços:** quando a solicitação chega a `COMPLETED` ou `FAILED`, gravar um evento numa tabela `outbox_event` na mesma transação do desfecho e publicá-lo num tópico (Kafka, SNS/SQS, Service Bus) por um relay ou CDC (Debezium). Assim o evento nunca se perde nem é publicado sem o estado ter mudado. Consumidores devem ser idempotentes pelo `id` da solicitação.
- **CI/CD:** o GitHub Actions deste repositório já roda `./mvnw verify` (com Testcontainers), constrói a imagem e varre segredos (gitleaks) e configuração (Trivy). Até o deploy, eu acrescentaria: varredura de vulnerabilidades da imagem, publicação no registry com tag do commit, deploy automático em homologação via GitOps (Argo CD), smoke test, e promoção para produção com aprovação e canário.

## Pontos pendentes

- **Limite de taxa:** implementado só o limite de corpo. O rate limit por consumidor ficaria no API Gateway ou no ingress (por `client_id`), que é onde ele protege também contra tráfego que nem chega à aplicação. Dentro do serviço daria para usar o `RateLimiter` do Resilience4j por consumidor.
- **Contrato do provedor:** `POST /provider/v1/activate` foi modelado com corpo JSON e `Idempotency-Key`. O formato real da resposta, o suporte à chave de idempotência e o significado de um 409 do provedor precisam ser confirmados com ele.
- **Paralelismo do worker:** cada instância processa o lote em sequência; um provedor lento atrasa o lote da instância. Com volume alto, processaria o lote em virtual threads com um bulkhead limitando chamadas simultâneas.
- **Métrica de solicitações por status:** existe a contagem de desfechos do provedor; um gauge por status (consulta agregada periódica) e a idade da fila ficaram de fora.
- **Evento de ativação concluída:** descrito acima, não implementado.

## Melhorias futuras

- Paginação por cursor (`created_at`, `id`) no lugar de `OFFSET` para listas grandes, e `count` opcional.
- Particionar ou arquivar solicitações finalizadas antigas para manter a tabela e os índices pequenos.
- Métrica e alerta da idade da fila e de solicitações em `FAILED` por motivo.
- Contrato OpenAPI publicado (springdoc) e testes de contrato com o provedor (Pact).
- Teste de carga (Gatling/k6) com o provedor simulado lento, para calibrar timeouts, lote e circuit breaker.
- Ao final das tentativas, uma fila de "dead letter" operável (reprocessar manualmente uma `FAILED`).

## Uso de IA

Este projeto foi desenvolvido com o Claude (Anthropic) como assistente. Ele foi usado para adaptar meu projeto base (Java 25 e Spring Boot 4) para Java 21 e Spring Boot 3.5, escrever boa parte do código de produção e de teste a partir de um plano que defini, revisar casos de borda (timeout ambíguo, `ON CONFLICT` sob concorrência, `SKIP LOCKED`) e redigir este README e os ADRs. Revisei o resultado, executei a suíte completa e as decisões registradas aqui são as que eu defendo na conversa técnica.
