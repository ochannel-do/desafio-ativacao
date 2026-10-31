# Keycloak do home lab

Realm: `desafio-ativacao`. Cada empresa corresponde a um cliente confidencial, com segredo próprio, usando exclusivamente `client_credentials`. O arquivo `desafio-ativacao-realm.json` pode ser importado ao criar um realm novo; não contém segredos. O Keycloak gera as credenciais na importação. Não reimportar sobre um realm existente para fazer manutenção.

| Cliente | Audiência | Escopos opcionais permitidos |
| --- | --- | --- |
| `empresa-a` | `ativacao-api` | `activation:read`, `activation:write` |
| `empresa-b` | `ativacao-api` | `activation:read`, `activation:write` |

O token é assinado com RS256 e vale por cinco minutos. O identificador vem em `azp`; a API já usa `client_id`, depois `azp`, depois `sub` como fallback. Nenhuma alteração no domínio ou nas rotas é necessária. O isolamento é aplicado pela API nas consultas ao banco. Os escopos aparecem no claim `scope`, usado pelo Spring Security, e devem ser solicitados explicitamente ao obter o token. As empresas de demonstração podem solicitar ambos; para restringir uma delas, remover a associação do escopo no Keycloak.

## Obter um token

No painel [Keycloak](https://keycloak.olivetree.dev.br/admin/master/console/#/desafio-ativacao/clients), abrir o cliente e a aba **Credentials**. Usar o segredo apenas da empresa correspondente.

Na raiz do projeto:

```sh
TOKEN=$(python3 scripts/token-keycloak.py empresa-a)
```

As credenciais das duas empresas foram guardadas em arquivos `empresa-a.json` e `empresa-b.json` na mesma pasta privada, com permissão `0600`, fora do repositório. Também é possível consultar cada segredo na aba **Credentials** do Keycloak.

O script pede o segredo sem exibi-lo nem colocá-lo nos argumentos do processo. A saída é somente o JWT, que pode ser usado em `Authorization: Bearer` ou no botão **Authorize** do Swagger UI. Para emitir o token da outra empresa, trocar para `empresa-b`. Para testar permissões, acrescentar `--scope activation:read`.

Se o Python não confiar no certificado do endpoint, fornecer um conjunto PEM de autoridades públicas confiáveis com `--ca-cert /caminho/ca.crt`. O script verifica HTTPS e não oferece opção para ignorar o certificado.

Endpoint de token: `https://keycloak.olivetree.dev.br/realms/desafio-ativacao/protocol/openid-connect/token`.

## Configuração da API no K3s

O KV v2 `secret/ativacao/hml/config` do OpenBao deve conter, preservando os campos de banco e demais configurações:

```text
AUTH_ISSUER=https://keycloak.olivetree.dev.br/realms/desafio-ativacao
AUTH_AUDIENCE=ativacao-api
AUTH_JWK_SET_URI=http://keycloak.keycloak.svc.cluster.local/realms/desafio-ativacao/protocol/openid-connect/certs
```

Remover `AUTH_HMAC_KEY`: a configuração exige exatamente um método de validação. Manter o perfil `local` desativado. O ExternalSecret sincroniza o Secret consumido pelo Deployment; depois da sincronização, reiniciar o Deployment para carregar o novo ambiente.

O endereço do emissor precisa coincidir exatamente com `iss` no token. O endereço de JWKS pode ser diferente: no laboratório, a API busca somente as **chaves públicas** pelo Service interno HTTP. Esse caminho depende da confiança na rede do cluster: não há autenticação TLS do servidor de chaves. Credenciais e tokens de empresas devem usar o endpoint público HTTPS. Para ambientes que exigem proteção também no tráfego interno, configurar TLS no caminho de JWKS e a confiança da JVM na CA antes de usar essa configuração.

Aplicar `kubectl apply -f k8s/bootstrap/keycloak-jwks.yaml` antes de trocar a configuração. Essa regra adicional permite saída dos pods de ativação apenas para os pods `app=keycloak` no namespace `keycloak`, porta 8080. É um recurso de bootstrap, separado dos recursos reconciliados pelo Argo CD. Não abre um novo acesso de entrada à API.

As credenciais de cada empresa ficam sob gestão do Keycloak e não são necessárias no ambiente da API: a API precisa somente das chaves públicas para verificar os tokens.

## Verificação

Com tokens reais de cada empresa:

1. Criar uma solicitação pela empresa A: `201`; consultá-la com A: `200`.
2. Consultar esse identificador com B: `404`; a listagem de B não contém a solicitação de A.
3. Repetir invertendo as empresas.
4. Com token de `activation:read`, tentar criar: `403`.
5. Sem token: `401`.

A migração não transfere registros antigos para as novas identidades: solicitações cujo consumidor era `sistema-a`, por exemplo, continuam pertencendo a esse consumidor. Os clientes novos começam com suas próprias solicitações. A unicidade de `externalId` continua global: usar valores distintos entre empresas nos testes.

Para voltar ao emissor anterior, restaurar os campos de autenticação da versão anterior no OpenBao, aguardar a sincronização do Secret e reiniciar a aplicação. Isso não desfaz registros criados durante a validação.

Referência: [guia oficial do Keycloak](https://www.keycloak.org/docs/latest/server_admin/index.html), seções de service accounts, client scopes e audience mapper.

## Validação em 31/10/2026

Executado no home lab com tokens reais dos dois clientes: criação `201`, consulta própria `200`, consulta cruzada `404`, listagens isoladas, criação com escopo somente de leitura `403` e acesso sem token `401`. O script de emissão foi exercitado para ambos os clientes pelo endpoint HTTPS, validando o certificado com a CA local. A aplicação ficou saudável após a troca de autenticação. Não houve alteração de código Java; a suíte Maven não foi executada nesta configuração operacional.

## Swagger público

A aplicação é publicada em `https://desafio-ativacao.olivetree.dev.br`. O realm tem `Frontend URL=https://keycloak.olivetree.dev.br`, e os dois clientes permitem somente `https://desafio-ativacao.olivetree.dev.br` em Web origins. Os demais realms mantêm seus endereços próprios. O Swagger usa `SWAGGER_OAUTH_TOKEN_URL=https://keycloak.olivetree.dev.br/realms/desafio-ativacao/protocol/openid-connect/token`. Veja o [guia de autorização](../security/swagger-keycloak.md).
