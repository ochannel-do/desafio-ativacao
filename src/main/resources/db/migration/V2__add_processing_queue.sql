-- A própria tabela é a fila de envio ao provedor (outbox): a linha PENDING gravada no mesmo
-- commit do POST é a intenção durável de chamar o provedor.
alter table activation_request
  add column next_attempt_at timestamptz,
  add column last_error      varchar(255),
  add column correlation_id  varchar(64);

update activation_request set next_attempt_at = created_at;
alter table activation_request alter column next_attempt_at set not null;

-- Índices parciais: só as linhas que o worker e a reconciliação varrem.
create index ix_activation_request_due on activation_request (next_attempt_at)
  where status = 'PENDING';
create index ix_activation_request_stuck on activation_request (updated_at)
  where status = 'PROCESSING';
