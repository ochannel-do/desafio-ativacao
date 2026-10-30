# ADR 003 — Integração assíncrona com o provedor

Status: parcialmente substituída pelo [ADR 005](005-kafka-outbox-inbox.md): Kafka, outbox e inbox substituem a tabela de solicitações como fila. Mantidas as regras do provedor, retentativa e transações. Data: 2026-10-28.

**Contexto.** O provedor responde em 400 ms normalmente e pode chegar a 3 s ou cair. A solicitação gravada precisa chegar a ele mesmo se a aplicação cair entre o commit e a chamada, e um timeout não diz se o produto foi ativado.

**Decisão.**
- O `POST` só grava a solicitação como `PENDING` e responde. A linha `PENDING` é o outbox: a própria tabela é a fila, sem tabela extra nem broker.
- Um agendador em cada instância reserva lotes com `SELECT ... FOR UPDATE SKIP LOCKED`, marca `PROCESSING` e confirma; chama o provedor fora de transação; grava o desfecho em outra transação, protegida por `@Version`.
- `Idempotency-Key` = id da solicitação, igual em todas as tentativas.
- Falhas temporárias voltam para `PENDING` com backoff exponencial e jitter (2 s até 5 min, 5 tentativas), respeitando `Retry-After`.
- Circuit breaker do Resilience4j em volta do cliente HTTP, contando só falhas do provedor.
- Reconciliação devolve para a fila o que ficou em `PROCESSING` além de 60 s.

**Alternativas descartadas.** Chamada síncrona no `POST`: acopla latência e disponibilidade da API às do provedor. `@Async`/evento em memória após o commit: perde a solicitação se a instância cair. Broker (Kafka/SQS): resolve, mas é mais infraestrutura do que o volume do desafio pede; continua sendo o passo natural para notificar outros serviços. `@Retry` do Resilience4j: segura a thread e não sobrevive à queda da instância.

**Consequência.** Latência do `POST` independente do provedor; o tempo até `COMPLETED` depende do intervalo do agendador (1 s) e do provedor. Escala horizontal sem coordenação extra.
