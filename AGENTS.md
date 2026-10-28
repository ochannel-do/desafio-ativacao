# Orientação para alterações

- Java 21 e Spring Boot 3.5. Build e testes: `./mvnw verify`; formatar: `./mvnw spotless:apply`.
- Comportamento novo ou correção: commit `test:` com o cenário falhando pelo motivo esperado, depois `feat:`/`fix:` com a implementação. Commits pequenos, mensagem explicando a intenção.
- `core.domain` e `core.application` ficam livres de Spring, JPA, HTTP e tracing. Domínio não depende de application. ArchUnit verifica.
- Portas de entrada e seus records são contratos públicos. HTTP, JPA, cliente do provedor, transação e observabilidade ficam em `infrastructure`.
- Composição explícita em `infrastructure.config`: apenas a porta decorada é bean.
- Entidades JPA separadas do domínio e mapeadas explicitamente. Status muda só por métodos do domínio, nunca por setter.
- Nenhuma chamada HTTP dentro de transação de banco aberta.
- Integração usa PostgreSQL real via Testcontainers e Flyway, nunca H2. Provedor externo em testes via WireMock.
- Crie apenas abstrações necessárias. Resiliência (circuit breaker, retry) só no adaptador do provedor externo.
- Toda leitura filtra pelo consumidor do token (client_id). Nova rota precisa de regra de escopo em `SegurancaConfiguration`.
