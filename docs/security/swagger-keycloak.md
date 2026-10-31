# Autorizar pelo Swagger com Keycloak

O Swagger pode solicitar um token pelo fluxo OAuth2 `client_credentials`. Configure `SWAGGER_OAUTH_TOKEN_URL` com o endpoint de token do realm. Sem essa variável, a documentação mantém a opção de colar um JWT, usada no desenvolvimento local.

Para o realm `desafio-ativacao`, com o Keycloak publicado em um endereço HTTPS, o endpoint segue este formato:

```text
https://keycloak.olivetree.dev.br/realms/desafio-ativacao/protocol/openid-connect/token
```

O navegador precisa conseguir acessar esse endereço e confiar em seu certificado. Um nome privado como `keycloak.homelab` só funciona nas redes em que ele é resolvido e acessível. Se a aplicação for publicada na internet, usar o endereço HTTPS público do Keycloak e um certificado válido para esse endereço.

## Configurar o Keycloak

Em **Clients → empresa-a → Settings → Web origins**, permitir a origem exata do Swagger: esquema, hostname e porta, sem caminho nem barra final. Repetir para `empresa-b`. Por exemplo, se o Swagger está em `https://ativacao.seudominio.com/swagger-ui/index.html`, a origem é `https://ativacao.seudominio.com`.

Para o túnel de homologação descrito no projeto, a origem é `http://localhost:18080`. Para a execução local na porta padrão, `http://localhost:8080`. Autorizar somente os endereços usados, sem `*`. Não é necessário habilitar login de usuários ou cadastrar redirect URIs para o fluxo `client_credentials`.

Ao mudar o endereço público do Keycloak, atualizar também `AUTH_ISSUER` para coincidir exatamente com o `iss` emitido nos tokens. A mudança do endereço do Swagger, isoladamente, não exige mudar o emissor. `AUTH_JWK_SET_URI` continua sendo o endereço alcançável pela API para consultar as chaves públicas; não é o endpoint usado pelo navegador para obter tokens.

## Usar

1. Abrir o Swagger e clicar em **Authorize**.
2. Na seção **keycloak**, preencher `client_id` com `empresa-a` ou `empresa-b` e informar o segredo correspondente.
3. As permissões `activation:read` e `activation:write` já aparecem selecionadas. Se precisar apenas consultar, pode desmarcar a escrita.
4. Clicar em **Authorize** e fechar a janela. O Swagger envia o token nas chamadas.
5. Para trocar de empresa, clicar em **Logout** e informar as outras credenciais. Quando o token expirar, autorizar novamente.

Com o Keycloak configurado, o Swagger exibe somente a autorização da empresa, sem o campo para colar um token. A opção **bearerAuth** fica disponível somente no desenvolvimento sem servidor de tokens.

Não há segredo de cliente configurado no código, no OpenAPI ou nos manifests. Cada pessoa que testa informa a credencial da empresa no momento do uso. A configuração desativa a persistência da autorização após recarregar a página.

## Entrega

O OpenBao fornece `SWAGGER_OAUTH_TOKEN_URL` junto de `AUTH_ISSUER`, sincronizados pelo ExternalSecret de homologação. Ajustar seu valor ao endereço real do Keycloak antes da publicação pública. A alteração do Swagger entra em vigor quando a nova versão da aplicação é implantada; mudanças apenas na configuração de um pod existente exigem reinício para carregar as variáveis.

Referências: [OAuth2 no Swagger UI](https://swagger.io/docs/open-source-tools/swagger-ui/usage/oauth2/) e [administração do Keycloak](https://www.keycloak.org/docs/latest/server_admin/index.html).

## Endereços deste laboratório

- Swagger: `https://desafio-ativacao.olivetree.dev.br/swagger-ui/index.html`.
- Keycloak: `https://keycloak.olivetree.dev.br`.
- Origem permitida nos dois clientes: `https://desafio-ativacao.olivetree.dev.br`.
- Emissor do realm: `https://keycloak.olivetree.dev.br/realms/desafio-ativacao`.

O OpenAPI usa servidor relativo `/`, para as chamadas acompanharem o HTTPS e o domínio em que o Swagger está aberto, inclusive atrás do Cloudflare Tunnel. Os túneis encaminham para os Services internos da aplicação e do Keycloak por HTTP. O endpoint de chaves usado pela API permanece interno ao cluster.
