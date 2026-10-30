create table activation_outbox (
  event_id uuid primary key,
  request_id uuid not null references activation_request(id),
  client_id varchar(128) not null,
  request_version bigint not null check (request_version >= 0),
  correlation_id varchar(64),
  available_at timestamptz not null,
  published_at timestamptz,
  lease_token uuid,
  lease_until timestamptz,
  unique (request_id, request_version)
);
create index ix_activation_outbox_due on activation_outbox(available_at, event_id) where published_at is null;

-- Migrar intenções pendentes existentes. O worker antigo deve estar parado antes da migração.
insert into activation_outbox(event_id, request_id, client_id, request_version, correlation_id, available_at)
select gen_random_uuid(), id, client_id, version, correlation_id, next_attempt_at
from activation_request where status = 'PENDING';
