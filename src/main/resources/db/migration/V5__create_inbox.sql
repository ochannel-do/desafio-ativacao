create table activation_inbox (
  consumer_name varchar(128) not null,
  event_id uuid not null,
  request_id uuid not null,
  client_id varchar(128) not null,
  request_version bigint not null check(request_version >= 0),
  lease_token uuid not null,
  status varchar(16) not null check(status in ('PROCESSING', 'COMPLETED', 'RECOVERED')),
  created_at timestamptz not null,
  finished_at timestamptz,
  primary key(consumer_name, event_id)
);
create index ix_activation_inbox_processing on activation_inbox(request_id, request_version) where status = 'PROCESSING';
