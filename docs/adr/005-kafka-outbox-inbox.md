# ADR 005 — Kafka com outbox e inbox

Status: aceita. Data: 2026-10-30.

## Contexto

Evoluir o serviço para publicar solicitações no Kafka e consumir ativações no K3s, preservando os contratos HTTP, a recuperação após falhas e a independência do core. O cluster do laboratório tem um nó; Kafka ainda não estava instalado no diagnóstico inicial.

## Decisão

- Uma aplicação, um módulo e uma imagem. API, publicador e listener são adaptadores do mesmo core.
- `activation_request` e `activation_outbox` são gravadas atomicamente. O adaptador do repositório agenda outbox ao inserir ou persistir `PENDING`; isso também cobre retentativas e reconciliação.
- O evento v1 contém `eventId`, `requestId`, `clientId`, versão esperada e correlação. Chave Kafka igual ao id da solicitação. Não contém dados de cliente/produto usados no HTTP: o consumidor os lê no banco pelo id e `client_id`.
- Publicador reserva uma linha por vez com `SKIP LOCKED` e lease de 30 s. Envia sem transação de banco, aguarda confirmação até 10 s e marca publicação com token de posse. Falha mantém a intenção e adia o próximo envio. Um crash após confirmação do broker pode republicar o mesmo `eventId`.
- Inbox com chave `(consumer_name, event_id)`. Consumidor lógico fixo `ativacao-provedor-v1`, independente do pod e do grupo Kafka por ambiente. Banco e namespace isolam os ambientes.
- Reserva da inbox e transição `PENDING → PROCESSING` compartilham uma transação. A reserva tem token próprio; seu prazo é o da solicitação `PROCESSING`. Mensagem repetida em andamento não é confirmada como concluída.
- HTTP ao provedor sempre fora de transação. Gravar desfecho, eventual próxima outbox e conclusão da inbox na mesma transação. `AckMode.RECORD`, auto-commit desligado e um registro por poll: confirmar offset somente após retorno do caso de uso.
- Cada tentativa de negócio gera novo `eventId` e versão da solicitação. Republicação mantém o id. A outbox respeita `next_attempt_at`; Kafka não é usado como temporizador.
- Reconciliação encerra inbox como `RECOVERED` e persiste recuperação/retentativa atomicamente. Locks seguem a ordem solicitação → inbox. Worker antigo não grava desfecho se a versão reservada mudou; conclusão da inbox também exige o token original.
- Mensagens antigas, de estado final ou sem solicitação pertencente ao consumidor são concluídas sem HTTP. Reutilizar o mesmo `eventId` com outro conteúdo é erro de envelope.
- Envelope inválido vai ao tópico `.dlt` com confirmação de publicação obrigatória. Falha técnica transitória (banco/infraestrutura/reserva ativa) mantém reentrega, sem consumir tentativas de negócio. Não enviar falha temporária de banco à DLT após um limite arbitrário e abandonar uma solicitação pendente.
- Tópicos e DLT possuem três partições, fator de replicação configurável (1 no laboratório). Listener começa com uma thread por instância.
- Preservar a porta legada de lote, mas retirar seu agendamento. O consumidor por mensagem é o caminho normal de processamento.

## Migração e operação

V4 cria outbox e gera um evento para cada `PENDING` já existente; V5 cria inbox. Parar as instâncias antigas antes da primeira migração para Kafka. `PROCESSING` existente é recuperado pela reconciliação, que cria a outbox necessária. Após a migração, não voltar à imagem com worker antigo sem um procedimento específico.

Outbox, inbox e solicitações ficam no mesmo PostgreSQL. As novas tabelas usam JDBC com mapeamento explícito; a entidade JPA das solicitações continua separada do domínio. A criação de beans fica em `infrastructure.config`; core não depende de Spring/Kafka/JPA.

No laboratório, um broker/controller KRaft com PVC e acesso interno limitado por NetworkPolicy. Sem alta disponibilidade, SASL ou TLS neste primeiro ambiente: isolamento de rede deve ser validado no cluster antes de uso. Acesso externo ao broker não é provisionado. Para dados/consumidores de produção, preparar autenticação, TLS, ACLs e topologia adequada antes de promover esse manifesto.

GitHub Actions verifica, constrói e analisa a imagem; publica no GHCR e atualiza digest na branch `deploy`. Argo CD sincroniza a aplicação de homologação. Infraestrutura Kafka acompanha seu próprio caminho em `main`. OpenBao e External Secrets fornecem credenciais; sua recuperação é pré-requisito do deploy da aplicação.

## Garantias e limites

Entrega pelo menos uma vez. Inbox e versão evitam repetir processamento já persistido, mas não tornam HTTP e PostgreSQL uma transação atômica. Queda depois do HTTP e antes do commit pode repetir a chamada; `Idempotency-Key=requestId` precisa ser honrada pelo provedor real. WireMock atual simula respostas, sem persistir deduplicação do lado do provedor.

Não há limpeza automática de inbox/outbox nesta mudança. Definir retenção considerando replay, DLT e auditoria antes de remover registros. Monitorar backlog/idade da outbox, lag e DLT. Métricas adicionadas: publicações, falhas da outbox e encaminhamentos à DLT.

## Validação

Testcontainers para PostgreSQL e Kafka, WireMock para HTTP, testes de rollback conjunto, replay, interrupção do worker, Retry-After, Kafka indisponível e DLT. ArchUnit mantém core puro e ausência de ciclos. `./mvnw verify` é a verificação obrigatória.
