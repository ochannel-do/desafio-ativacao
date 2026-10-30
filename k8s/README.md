# Ativação no home lab

## Entrega automática

`main` → testes/formatação/ArchUnit/Gitleaks/Trivy → imagem amd64 analisada → GHCR → digest na branch `deploy` → Argo CD → K3s.

O pipeline não acessa a rede doméstica e não recebe kubeconfig. A atualização do manifesto exige que os checks e a publicação terminem com sucesso. A primeira publicação não instala recursos no cluster: cadastrar as Applications após o bootstrap. O Argo CD valida o estado do rollout; o Actions não afirma que a aplicação ficou saudável apenas porque publicou a imagem.

- Aplicação: `k8s/overlays/hml`, namespace `ativacao-hml`, branch `deploy`.
- Kafka: `k8s/kafka`, namespace `messaging`, branch `main`, Application própria.
- Segredos, namespaces e banco: preparação fora da Application da aplicação.
- Configuração do Argo CD: `k8s/argocd/applications.yaml`.

Os manifests usam imagem `bootstrap` como marcador inicial. O primeiro pipeline substitui esse marcador por digest antes do cadastro da Application de ativação. Não aplicar a aplicação com esse marcador.

## Pré-requisitos do servidor

Acesso conhecido: `ssh ochannel@192.168.1.80`. O usuário possui kubeconfig próprio:

```sh
export KUBECONFIG="$HOME/.kube/config"
kubectl get nodes
```

No diagnóstico de 30/10/2026, K3s e Argo CD estavam saudáveis. OpenBao estava selado e External Secrets não sincronizava. Desselar o cofre no terminal privado usando as chaves sob guarda do responsável; não registrar chaves ou tokens no Git/chat. Validar SecretStore e ExternalSecret antes do rollout.

## Bootstrap

1. Aplicar `k8s/bootstrap/namespaces.yaml`.
2. Criar banco `ativacao_hml` e usuário exclusivo na instância `databases-hml/postgresql`. O usuário deve ser proprietário do banco/schema para Flyway. Fazer isso com credencial administrativa no terminal privado. Não reutilizar banco/usuário de `pedidobasecode` e não executar SQL com segredos em argumentos de processos ou histórico.
3. Configurar no OpenBao KV v2 `secret/ativacao/hml/config` os campos `DB_USER`, `DB_PASSWORD`, `AUTH_ISSUER` e `AUTH_AUDIENCE=ativacao-api`. Para OAuth real, acrescentar `AUTH_JWK_SET_URI`; para homologação sem servidor de identidade, usar `AUTH_HMAC_KEY` aleatória com no mínimo 32 bytes e `AUTH_ISSUER=http://localhost/local`. Nunca usar a chave pública de desenvolvimento no cluster nem ativar o perfil `local`.
4. Criar policy OpenBao que permita somente `read` em `secret/data/ativacao/hml/config`, vinculada à role Kubernetes `ativacao-hml`, ServiceAccount `openbao-reader`, namespace `ativacao-hml`. Aproveitar o mount de autenticação Kubernetes já existente; não substituir a configuração global do cofre.
5. Copiar apenas o certificado público da CA para ConfigMap `openbao-ca`, chave `ca.crt`, no namespace `ativacao-hml`. O SecretStore existente em `pedidobasecode-hml` usa esse mesmo padrão; verificar validade da CA após recuperar o cofre.
6. Aplicar `k8s/bootstrap/external-secrets.yaml`; aguardar `SecretStore/openbao` e `ExternalSecret/ativacao-config` em `Ready=True`.
7. Garantir acesso de leitura ao pacote GHCR. Se o pacote for privado, criar `ghcr-pull` no namespace e configurar `imagePullSecrets` no Deployment via overlay. Usar credencial somente de leitura com acesso a esse pacote. Repositório público não torna automaticamente o pacote público.
8. Confirmar que a branch `deploy` recebeu um digest válido. Aplicar inicialmente apenas o AppProject e a Application `ativacao-kafka` de `k8s/argocd/applications.yaml`; aguardar Kafka Ready e validar produção/consumo interno. Depois cadastrar `ativacao-hml`.

Não instalar uma segunda instância de Argo CD nem PostgreSQL para este projeto. São três novos pods no desenho inicial: aplicação, Kafka e WireMock. Banco, cofre e observabilidade são compartilhados como infraestrutura, com identidades/dados isolados.

## Kafka do laboratório

Um broker/controller KRaft, PVC `10Gi`, retenção de 24 horas e limite de 256 MiB por partição. Sem alta disponibilidade: perda do nó/disco exige recuperação. StorageClass `local-path`, sem assumir snapshots ou backup já existentes. Não apagar PVC para atualizar imagem nem alterar o `CLUSTER_ID` de um volume existente.

O broker aceita somente tráfego interno permitido pela NetworkPolicy: aplicação `ativacao-hml` na porta 9092 e o próprio Kafka nas portas de broker/controller. O manifesto inicial usa PLAINTEXT sem SASL, adequado ao laboratório isolado; validar aplicação efetiva das policies no K3s. Para promoção além do laboratório, configurar TLS/SASL/ACLs antes de admitir outros consumidores. Não há NodePort, LoadBalancer ou ingress para Kafka.

Limites iniciais: Kafka até 1,5 CPU/1,5 GiB; aplicação até 1 CPU/768 MiB; WireMock até 0,5 CPU/384 MiB. Medir heap, disco, lag e latência antes de aumentar concorrência. `max.poll.records=1`; três partições disponíveis para repartir consumo entre instâncias.

## Validação e acesso

```sh
kubectl -n argocd get applications ativacao-kafka ativacao-hml
kubectl -n messaging rollout status statefulset/kafka --timeout=180s
kubectl -n ativacao-hml wait --for=condition=Ready externalsecret/ativacao-config --timeout=120s
kubectl -n ativacao-hml rollout status deployment/ativacao --timeout=180s
kubectl -n ativacao-hml port-forward svc/ativacao 8080:80 --address 127.0.0.1
```

No Mac, abrir túnel para o port-forward do servidor:

```sh
ssh -L 18080:127.0.0.1:8080 ochannel@192.168.1.80
```

Consultar `http://localhost:18080/actuator/health/readiness`. Para API, obter JWT do emissor configurado. Na alternativa HMAC de homologação, `scripts/token-local.sh` funciona com a chave de homologação fornecida somente no ambiente do terminal privado e emissor `http://localhost/local`.

Enviar uma solicitação com token válido, consultar até `COMPLETED` e verificar inbox/outbox. Exercitar `INVALID-*`, `BUSY-*` e `SLOW-*` conforme os stubs do WireMock. A interface administrativa do mock não é exposta ao público; o Service é ClusterIP. Ao alterar os stubs, sincronizar o ConfigMap de `k8s/base/provider-mock.yaml` com `wiremock/mappings`.

Traces vão ao Alloy por OTLP HTTP. O Deployment contém anotações de scrape; verificar que a configuração atual do Prometheus descobre o novo namespace (anotações sozinhas não garantem coleta). Métricas adicionais: `ativacao_outbox_publicadas_total`, `ativacao_outbox_falhas_total`, `ativacao_kafka_dlt_total`; monitorar também idade/backlog da outbox e lag do consumidor.

## Recuperação, replay e rollback

Parar a versão antiga com polling antes da migração inicial. Flyway V4 agenda os PENDING existentes; a reconciliação recupera PROCESSING antigo. Nunca deixar worker legado e consumidor Kafka ativos ao mesmo tempo.

Replay do mesmo evento mantém `eventId`, identidade, versão e chave Kafka; inbox concluída evita novo HTTP. Nova tentativa de negócio gera novo evento automaticamente. DLT recebe envelopes inválidos, que exigem investigação/correção; não habilitar reenvio automático de payload inválido. Falhas técnicas transitórias mantêm o offset pendente para nova entrega.

Reverter digest na branch `deploy` permite rollback para uma imagem compatível com Kafka e com as migrations já aplicadas. Isso não desfaz schema nem dados; não voltar à versão com agendador legado sem plano de migração. Não habilitar limpeza automática da inbox/outbox sem definir a janela de replay e retenção da DLT.
