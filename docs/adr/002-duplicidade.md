# ADR 002 — Duplicidade de solicitações

Status: aceita. Data: 2026-10-28.

**Contexto.** Sistemas de origem fazem retry e podem enviar o mesmo `externalId` duas vezes quase ao mesmo tempo. Consultar antes de inserir deixa uma janela em que as duas requisições passam pela consulta e as duas inserem.

**Decisão.** Constraint `unique (external_id)` no PostgreSQL como fonte da verdade e `INSERT ... ON CONFLICT (external_id) DO NOTHING`. Com 0 linhas inseridas, o serviço lê o registro existente: mesmo consumidor e mesmo conteúdo responde `200` com o original; qualquer divergência responde `409` sem dados do registro.

**Por que ON CONFLICT e não capturar a violação.** No PostgreSQL uma violação de constraint aborta a transação; seria preciso savepoint ou nova transação para ler o registro existente. O `ON CONFLICT` espera a transação concorrente terminar e devolve 0 linhas com a transação ainda válida.

**Alternativas descartadas.** Lock em memória (`synchronized`, mapa de locks): não vale com mais de uma instância. Lock distribuído (Redis): infraestrutura extra para algo que o banco já garante. `409` para todo retry: obrigaria o cliente a tratar como erro uma operação que deu certo.

**Consequência.** A unicidade é global, como pede o enunciado. Se o negócio decidir que `externalId` é único por sistema de origem, basta trocar a constraint para `(client_id, external_id)`.
