# Validation Flow Engine

Motor backend para **definir, versionar e executar fluxos de validação antifraude** sem alterar código. Um sistema chamador (crédito, abertura de conta, onboarding...) envia um JSON; o motor escolhe o fluxo ativo, percorre o grafo de validações e devolve uma decisão com a trilha de auditoria.

Spec de origem: [`tasks/validation-flow-engine-v0.1.md`](tasks/validation-flow-engine-v0.1.md). O que já cobre o MVP está em [`tasks/coverage.md`](tasks/coverage.md).

## Como funciona

1. Um analista cria um **fluxo** (grafo de nodes e transições) em `DRAFT`, valida a estrutura e **ativa** uma versão. Só uma versão fica ativa por seletor (`flowKey` + tipo de usuário + contexto).
2. Um sistema consumidor chama `POST /api/v1/executions`. O motor resolve o fluxo ativo, congela um **snapshot** da versão e executa o grafo de forma síncrona.
3. Cada node gera histórico (entrada, saída, tentativas, erro). Mudar o fluxo depois não altera execuções já feitas.

| Node | Função |
|---|---|
| `START` / `END` | início e fim do grafo |
| `VALIDATION` | roda um validador registrado (Strategy), com timeout e retry |
| `DECISION` | escolhe a transição por condição sobre `inputData` (papel, canal, grupo, atributo) |
| `SUB_FLOW` | chama outro fluxo, com mapeamento de entrada e saída |

Isolamento por **tenant**: a API key fixa o `tenantId`, e dois tenants podem ter a mesma `flowKey` sem colisão.

### Idempotência

O header opcional `Idempotency-Key` (escopo do tenant) evita executar a mesma validação duas vezes num retry: a repetição com o mesmo payload devolve a execução original (`Idempotent-Replayed: true`), payload diferente dá 409 e chamadas concorrentes rodam o fluxo uma vez. Se a chamada lançar exceção antes de criar a execução, a chave é liberada; uma execução que termina em falha é um resultado e é reaproveitada. A chave é lembrada pela janela `app.retention.idempotency`.

### Nodes e pausa

Além de START, END, VALIDATION, CONDITION e SUB_FLOW, o node `HUMAN_APPROVAL` (`reason`, `assignedGroup`, `deadline` ISO-8601 limitado por `app.limits.max-wait`) para a execução em `WAITING` (`POST /executions` responde 202). Exige transições com `label` `APPROVED` e `REJECTED`. `resume` segue a transição da decisão sobre o snapshot original; `cancel` só vale para `WAITING`. O job `app.wait.expiry-interval` (padrão PT1M) leva `WAITING` vencida a `TIMED_OUT` (`WAIT_EXPIRED`). Um `SUB_FLOW` cujo filho pausa deixa o pai em `WAITING`: decida no id do filho e o pai continua; cancelar ou expirar um lado cancela/expira o outro. Métricas: gauge `validation.execution.waiting` e contadores `validation.execution.resumed|cancelled|wait_expired`.

### Provedores assíncronos (callback e polling)

O node `EXTERNAL_CALL` (`providerKey`, `query`, `expect`, `maxWait`) chama um provedor sem bloquear: se o provedor responde `Pending`, o motor grava um `provider_call` e a execução fica `WAITING` (`PROVIDER_PENDING`) até `maxWait`. O resultado chega de duas formas:

- **Callback**: `POST /api/v1/provider-callbacks/{callId}`, fora do esquema de API key, autenticado pelo header `X-Callback-Token` (entregue ao provedor em `start`; só o hash fica no banco). Se o `provider_config` tem `callback_secret_ref`, o header `X-Signature` (HMAC-SHA256 hex do corpo) é exigido. Token inválido dá 401 sem revelar se a chamada existe; repetição é idempotente (200); chamada `DEAD`/`FAILED`/expirada dá 409.
- **Polling**: o job `app.providers.poll-interval` (PT10S) chama `poll` nas chamadas vencidas, com backoff exponencial até `app.providers.poll-max-delay` (PT5M); erro retryable conta em `attempts` até `app.providers.max-attempts` (5).

Chamadas que falham ou cuja execução expirou/foi cancelada viram `DEAD` (com motivo). Operação (escopo `provider:admin`): `GET /api/v1/provider-calls?status=DEAD` e `POST /api/v1/provider-calls/{id}/retry` (só com a execução ainda `WAITING`, senão 409). Métricas: gauges `validation.provider.pending|dead` e contadores `validation.provider.callback|poll` com tags `provider` e `outcome`. Os logs trazem `executionId`, `callId`, `providerKey` e status, nunca o token nem o corpo.

### Cache de respostas de provedores

O cache só atua onde há **política**: `PUT /api/v1/providers/{providerKey}/cache-policies/default` (escopo `provider:admin`, auditado) com `dataCategory` `LOW_VOLATILITY|MEDIUM_VOLATILITY|HIGH_VOLATILITY|REAL_TIME|CUSTOM`. Sem política, ou com `REAL_TIME`, nada é gravado. O TTL vem da política (padrões `app.providers.cache-ttl.low|medium|high-volatility`; `CUSTOM` exige `ttlSeconds`, `justification`, `owner` e `reviewDate`) e nunca passa de `app.providers.cache-max-ttl` (P7D); resposta `found:false` vive no máximo `app.providers.negative-cache-ttl` (PT5M). A chave é o SHA-256 dos parâmetros canônicos (o documento em claro não é gravado) e a resposta guardada é só a normalizada pelo adapter, nunca o corpo bruto.

**Operação e chave.** O node declara `operation` em `config` (padrão `default`, `[A-Za-z0-9_.-]{1,100}`); cada operação tem sua própria política e TTL em `/cache-policies/{operation}`, e a conclusão assíncrona grava na operação do node de origem. A política pode declarar `keyFields` (lista de nomes de parâmetros; vazia, em branco ou duplicada dá 400): só eles entram no hash da chave; ausente (`null`) = todos os parâmetros. Antes do hash, o adapter normaliza os parâmetros (`DataProvider.cacheKeyParams`; na BrasilAPI o `cnpj` vira só dígitos, então CNPJ formatado e sem máscara compartilham a entrada). Se algum `keyField` faltar nos parâmetros da chamada, o cache não é usado nela (`DISABLED`, nada é gravado, aviso no log com o nome do campo, nunca o valor): nunca se monta chave parcial. Alterar `keyFields` muda o hash, então as entradas antigas ficam órfãs até expirarem por TTL ou pela limpeza. `cache` só aceita `"BYPASS"`; outro valor é rejeitado na ativação do grafo.

O resultado do node traz `cacheStatus` (`HIT|MISS|EXPIRED|BYPASS|DISABLED`), `cacheAgeSeconds` e `cachePolicy`. `"cache": "BYPASS"` no node força chamada nova e renova a entrada. Um HIT em `EXTERNAL_CALL` não cria `provider_call`. Com `fallbackOnErrorAllowed=true`, erro retryable esgotado serve o dado vencido marcado `STALE_FALLBACK` com `fallbackReason`. Limpeza: `app.retention.provider-cache` (carência após `valid_until`; desligada se ausente) roda a cada `app.retention.interval`. Métrica: contador `validation.provider.cache` com tags `provider` e `result` (`hit|miss|expired|bypass|stale_fallback`).

### Validadores e provedores externos

Validadores são beans `ValidatorStrategy` descobertos pelo registry (`GET /api/v1/node-types`). Para adicionar um, implemente a interface e registre o bean, sem tocar no executor.

| Chave | Tipo | Observação |
|---|---|---|
| `cpf-checksum`, `cnpj-checksum` | offline | dígitos verificadores (CNPJ alfanumérico incluso) |
| `brasilapi` | provedor externo | consulta a BrasilAPI (operações `cnpj` e `cep`) (gratuita, sem SLA); desligado por padrão, habilitado por tenant em `provider_config` |

Um **provedor** (`DataProvider`) só busca e normaliza dados. Quem decide se passa é o `ProviderBackedValidator`, configurado no node (`params.query` e `params.expect`). Timeout e retry são do motor (`ValidatorRunner`), então um provedor novo (BigDataCorp, Unico...) é um adapter em `adapter/out/provider/` mais um bean em `config/ProviderConfig`. Habilite por tenant na tabela `provider_config` (`enabled=true`).

## Stack

Java 17, Spring Boot 4.1, Spring Data JPA (Hibernate), PostgreSQL 17 com JSONB, Flyway, Spring Security (API key), Actuator/Micrometer, springdoc, Testcontainers e ArchUnit.

## Arquitetura

Pacote base `com.prevention.fraud.validationflow`, em camadas verificadas pelo ArchUnit (`architecture/ArchitectureTest`):

```text
domain                   records imutáveis: fluxo, execução, documentos
application              serviços e portas (flow, execution, validator, provider)
adapter.in.rest          controllers, DTOs, tratamento de erro
adapter.out.postgres     entidades JPA, repositórios, mappers
adapter.out.provider     clientes de provedores externos
config                   beans, segurança, retenção, recuperação
```

`application` e `domain` não dependem de Spring Data nem de JPA. Execuções interrompidas por queda do processo são recuperadas por um job (`app.recovery.*`).

## Rodando localmente

Pré-requisitos: JDK 17, Maven e Docker.

```bash
cp .env.example .env                       # ajuste as credenciais
docker compose up -d                       # PostgreSQL na porta DATABASE_PORT
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

O perfil `local` lê o `.env`. As migrations do Flyway rodam na subida. Sem perfil, as variáveis precisam estar no ambiente.

Autenticação: header `X-API-Key` com a chave do `.env`. A chave fixa o tenant e os escopos (`API_KEY_SCOPES`).

| Escopo | Libera |
|---|---|
| `flow:read` | listar e consultar fluxos e node types |
| `flow:write` | criar, atualizar e validar fluxos |
| `flow:activate` | ativar e arquivar |
| `validation:execute` | iniciar execução |
| `validation:read` | consultar execuções e histórico |
| `validation:resume` | retomar execução em espera (`POST /executions/{id}/resume`) |
| `validation:cancel` | cancelar execução em espera (`POST /executions/{id}/cancel`) |
| `provider:read` / `provider:admin` | listar / alterar a configuração de provedores (`/api/v1/providers`) |

Exemplo:

```bash
curl -X POST localhost:8081/api/v1/executions \
  -H 'X-API-Key: chave-de-desenvolvimento' -H 'Idempotency-Key: pedido-42' \
  -H 'Content-Type: application/json' \
  -d '{"userType":"PF","context":"ONBOARDING","inputData":{"cpf":"12345678909"}}'
```

O contrato completo está em [`openapi.yaml`](openapi.yaml). Com o perfil `local`, o Swagger UI fica em `/swagger-ui.html`. Health: `/api/v1/health` e `/actuator/health`. Métricas (exigem autenticação): `/actuator/metrics`, com `validation.execution`, `validation.node.*` e timers p50/p95/p99.

## Testes

```bash
mvn -B verify      # exige Docker (Testcontainers sobe o PostgreSQL)
```

Teste de carga: `loadtest/run.sh`. O baseline medido está em [`loadtest/REPORT.md`](loadtest/REPORT.md) (~650 req/s numa máquina só, sem meta de volume assumida).

## Limites e configuração

Propriedades `app.*` (valores padrão no código): `limits.*` (nós, transições, timeout, tentativas, tamanho do corpo), `validators.max-threads` e `queue-capacity`, `idempotency.lease`, `retention.idempotency`, `retention.provider-cache`, `recovery.*`.

## O que ainda não existe

Ficou fora do MVP por decisão: `fork/join`, particionamento e réplicas de leitura. Detalhes em [`tasks/coverage.md`](tasks/coverage.md).

## Desenvolvimento com Ralph

Os ciclos de implementação rodam pelo agente autônomo em `scripts/ralph/` (`ralph.sh`, `prd.json`, `progress.txt`). Os PRDs ficam em `tasks/` e os ciclos concluídos em `scripts/ralph/archive/`.
