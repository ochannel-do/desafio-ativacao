-- Dono da solicitação: o sistema cliente autenticado (claim client_id/sub do JWT).
-- Toda consulta passa a filtrar por ele, evitando acesso a registros de outro consumidor (BOLA).
alter table activation_request add column client_id varchar(128);
update activation_request set client_id = 'desconhecido' where client_id is null;
alter table activation_request alter column client_id set not null;

-- Listagens sempre começam pelo consumidor; os índices de V1 ficam redundantes.
drop index ix_activation_request_customer;
drop index ix_activation_request_status;
drop index ix_activation_request_created;
create index ix_activation_request_client_created
  on activation_request (client_id, created_at desc, id desc);
create index ix_activation_request_client_customer
  on activation_request (client_id, customer_id, created_at desc, id desc);
create index ix_activation_request_client_status
  on activation_request (client_id, status, created_at desc, id desc);
