# Análise da imagem — 30/10/2026

O scan HIGH/CRITICAL bloqueou a imagem original. Atualizados Tomcat 10.1.61, Jackson BOM 2.21.8, PostgreSQL JDBC 42.7.14, lz4-java 1.12.1 e snappy-java 1.1.10.12, mantendo Java 21 e Boot 3.5.16. Validar com a suíte completa e novo scan da imagem.

## Spring Framework 6.2.19

Dois alertas dependem de funcionalidades ausentes desta API:

- [CVE-2026-47884 — aviso oficial](https://spring.io/security/cve-2026-47884/): exige XsltView com resolução de view em mapping abrangente. A aplicação tem controllers REST, sem views/XSLT, e nega rotas sem regra de segurança.
- [CVE-2026-47890 — aviso oficial](https://spring.io/security/cve-2026-47890/): exige fragmentos de views enviados via SSE. Não há SSE nem fragmentos de views na aplicação.

Os avisos oficiais classificam os problemas como MEDIUM e LOW, respectivamente; o banco consultado pelo Trivy os classificou como CRITICAL. Para Spring 6.2, a correção 6.2.20 está disponível somente no suporte Enterprise; não misturar Spring 7 com Boot 3.5 para contornar essa restrição.

`.trivyignore.yaml` filtra somente esses dois CVEs no caminho exato `app/BOOT-INF/lib/spring-webmvc-6.2.19.jar`, com vencimento em 30/11/2026. Não declara a biblioteca corrigida e não vale como análise de outras aplicações. Foi escolhido o filtro por caminho porque o Trivy não atribui PURL OCI à imagem local ainda não publicada; uma declaração VEX vinculada ao repositório não se aplica nessa etapa. `ArchitectureTest.apiNaoIntroduzViewsOuSseSemRevisaoDeSeguranca` verifica as premissas: nenhuma dependência de views/emitters e nenhum controller MVC comum. Também revisar configurações e dependências ao introduzir plugins ou novas formas de renderização.

Trivy continua bloqueando outros achados HIGH/CRITICAL com correção disponível. Revisar esta análise quando mudar Spring, a superfície HTTP ou as funcionalidades usadas; remover as exceções ao atualizar para uma linha corrigida. Não há exclusão geral de pacote ou severidade.

[Trivy: filtros por caminho e vencimento](https://trivy.dev/docs/v0.56/configuration/filtering/). O workflow carrega `trivy.yaml` explicitamente; o gate continua HIGH/CRITICAL para outros achados com correção disponível.
