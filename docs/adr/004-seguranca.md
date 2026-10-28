# ADR 004 — Autenticação e autorização

Status: aceita. Data: 2026-10-28.

**Contexto.** A API é consumida por outros sistemas, não por pessoas. Um sistema não pode ver solicitações de outro.

**Decisão.**
- OAuth 2.0 Client Credentials; o serviço é Resource Server e valida JWT: assinatura pelo JWKS do emissor, `iss`, `aud = ativacao-api`, `exp`/`nbf`.
- Escopos `activation:write` (criar) e `activation:read` (consultar e listar). Rota sem regra é negada.
- O `client_id` do token é gravado como dono; leitura de registro alheio responde `404`.
- 401 e 403 no mesmo formato Problem Details da API.
- Corpo limitado a 16 KB.
- Local e testes: HS256 com chave simétrica, para não depender de servidor de autorização. Em produção só JWKS (assimétrico); a configuração exige exatamente um dos dois.

**Alternativas descartadas.** API key estática por cliente: sem expiração nem escopo, rotação manual. Token opaco com introspecção: uma chamada ao servidor de autorização por requisição.

**Consequência.** Nenhum segredo de cliente passa pelo serviço; rotação de chaves acontece no servidor de autorização, e o JWKS é recarregado automaticamente.
