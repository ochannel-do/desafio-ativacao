create table activation_request (
  id           uuid        primary key,
  external_id  varchar(64) not null,
  customer_id  varchar(64) not null,
  product_code varchar(64) not null,
  status       varchar(16) not null,
  attempts     integer     not null default 0,
  created_at   timestamptz not null,
  updated_at   timestamptz not null,
  version      bigint      not null default 0,
  -- Fonte da verdade contra duplicidade: vale para qualquer número de instâncias.
  constraint uk_activation_request_external_id unique (external_id),
  constraint ck_activation_request_status
    check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
  constraint ck_activation_request_attempts check (attempts >= 0)
);

-- Listagem filtrada e ordenada por created_at desc: cada índice cobre um filtro e a ordenação,
-- evitando sort em memória. Sem filtro, a listagem usa ix_activation_request_created.
create index ix_activation_request_customer on activation_request (customer_id, created_at desc, id desc);
create index ix_activation_request_status on activation_request (status, created_at desc, id desc);
create index ix_activation_request_created on activation_request (created_at desc, id desc);
