# Rastreabilidade: v0.1 (objetivo principal) x implementação

Referência: `00-v0.1-spec.md` (seção 15) e `01-prd-validation-flow-engine.md` (FR-1..FR-24).
Atualizar sempre que um PRD novo for fechado. Status: OK | LACUNA | ADIADO (fase X) | NÃO VERIFICADO.

## Lacunas do MVP

### GAP-1: Observabilidade (critério 11, FR-19): OK
- Contadores `validation.node.timeout|error|retry|rejected`, contador `validation.execution` (tag `status`) e timers `validation.execution.duration` e `validation.node.duration` (p50/p95/p99), via porta `ExecutionMetrics`.
- `/actuator/metrics` exposto e protegido pela autenticação existente (`management.endpoints.web.exposure.include`).
- Testes: `ExecutionTest.executionCounterAndTimersTrackCompletedAndFailed` e `ExecutionHttpTest.metricsAreExposedBehindAuthentication`.

### RF-08 (HUMAN_APPROVAL) e RF-12 (retomar/cancelar): OK
- Node `HUMAN_APPROVAL` pausa em `WAITING`; `POST /executions/{id}/resume` e `/cancel`; job de expiração (`WAIT_EXPIRED`).
- Métricas: gauge `validation.execution.waiting`, contadores `validation.execution.resumed|cancelled|wait_expired`.
- `SUB_FLOW` com filho em espera: o pai fica `WAITING` (`SUB_FLOW_WAITING`); decidir no filho continua o pai. Cancelar ou expirar um lado cancela/expira o outro em cascata (`CHILD_CANCELLED`, `PARENT_CANCELLED`, `WAIT_EXPIRED`), auditado nos dois; a recuperação de um filho abandonado cancela o pai (`CHILD_ABANDONED`). Os contadores contam cada execução afetada.
- Testes: `ExecutionTest` (resume, cancel, expiração, métricas).

### RF-07 (execução assíncrona) e RF-13 (cache de respostas): PARCIAL
Entregue (`06-prd-provedores-async-e-cache.md`, US-001..US-012 e ajustes posteriores):
- Node `EXTERNAL_CALL`, contrato `DataProvider.start/poll/normalizeCallback`, tabela `provider_call` (com FK para `flow_execution`), callback autenticado por token (HMAC opcional), polling com backoff e `SKIP LOCKED`, dead-letter (`DEAD`) e reprocessamento (`POST /provider-calls/{id}/retry`).
- Cache: `provider_cache_entry` e `provider_cache_policy`, TTL por categoria com teto `cache-max-ttl`, `REAL_TIME` sem cache, `BYPASS`, cache de `found:false` com TTL curto, fallback para dado vencido somente com permissão da política e registrado no resultado, limpeza por `valid_until`, métricas `validation.provider.*`.
- Callback e polling também gravam no cache; uma conclusão repetida após queda entre retomar e marcar `COMPLETED` termina sem 409.
- Índices: parcial por `status <> 'COMPLETED'` (sweep e gauges) e `(execution_id)` (FK).

Lacunas conhecidas:
- **Sem adapter assíncrono real:** só a BrasilAPI (síncrona) existe; callback, polling e dead-letter foram testados com provedores falsos.
- Falha de polling não faz fallback para dado vencido.
- Callback que chega depois do sweep de dead-letter (chamada já `DEAD`) recebe 409.
- RF-13 não coberto: política versionada, `sensitivity` (criptografia/retenção por política), invalidação por evento, TTL por produto/canal/tipo de usuário, aprovação de `CUSTOM`, camada Redis.
- Teste de carga: rodar fora de `/workspace` (ver `loadtest/REPORT.md`).

## Critérios do v0.1 (seção 15)

| # | Critério | Status |
|---|----------|--------|
| 1-10 | DRAFT, validação de grafo, 1 versão ativa, snapshot, executor, registry, JSONB, histórico, idempotência, sub-flow | OK (US-004..US-015) |
| 11 | Timeout, retry e erro observáveis | OK (GAP-1 fechado) |
| 12 | Health/readiness | OK (US-001) |
| 13 | Testes de grafo, seleção, transições, versionamento, execução | OK |
| 14 | Teste de carga inicial | OK (US-018) |
| 15 | Multi-tenancy sem colisão | OK (US-016) |
| 16 | Execução registra tenantId, contexto e versão | OK (`MultiTenancyTest` lê `tenant_id`, `flow_version` e `context_data` de `flow_execution`) |
| 17 | Transição por papel/canal/grupo/atributo | OK (`ConditionEvaluatorTest` por operador; `ExecutionTest` avalia `inputData.groups`/`inputData.rural` e audita `TRANSITION_EVALUATED`) |
| 18 | Grupos de documentos | OK (US-017) |

## Adiado por decisão (fases 3 a 5 do v0.1)

fork/join, ASYNC_VALIDATION,
particionamento, broker, réplicas de leitura, projeções,
aprovação em dois níveis para ativação.

## Persistência: decisões da migração JDBC → JPA

- **Mapeamento JSON:** `@JdbcTypeCode(SqlTypes.JSON)` nativo do Hibernate (Jackson 3 funciona); sem `AttributeConverter`. JSON nulo vira SQL NULL (antes, literal jsonb `null`); a leitura de domínio é idêntica.
- **Tradução de exceções:** violação de unique chega como `DataIntegrityViolationException`; `RestExceptionHandler` devolve 409 só quando SQLState 23505 está na cadeia, senão 500. `DuplicateKeyException` segue mapeada para 409.
- **Persistable:** entidades com id gerado pela aplicação estendem `AssignedIdEntity` (`Persistable<UUID>`), evitando SELECT antes do INSERT. `IdempotencyKeyEntity` usa `@EmbeddedId` e nunca é salva via `save`.
- **Lock otimista:** `flow_execution.update` continua `@Modifying` com `WHERE lock_version = ?`, sem `@Version`.
- **Idempotência:** o claim atômico permanece em SQL nativo (`INSERT ... ON CONFLICT DO UPDATE ... WHERE`).
- **Arquitetura:** regra ArchUnit restringe `jakarta.persistence` e `org.springframework.data` a `adapter.out.postgres`.

## Operações do provedor (`08-prd-operacoes-do-provedor.md`): OK
- `DataProvider.operations()` + variantes por operação; `provider_call.operation` (V12); resolução e checagem na ativação (`OPERATION_REQUIRED`, `UNKNOWN_OPERATION`, `MISSING_PARAMETER`, `UNKNOWN_PARAMETER`); cache `default` → operação única (V13); `brasilapi` com `cnpj` e `cep`, `brasilapi-cnpj` migrado e removido (V14); catálogo `GET /node-types` com `providers`.
- Testes: `DataProviderContractTest`, `OperationActivationTest`, `MigrationsTest`, `BrasilApiProviderTest`, `BrasilApiRenameMigrationTest`, `ValidatorRegistryTest`.
- Lacuna: a migração V14 só reescreve o grafo de execuções não terminais; relatórios históricos mantêm `brasilapi-cnpj` em execuções terminais por decisão.

## Decisões do doc 00 §17 (2026-10-20)

- **Sub-flow (§17.13):** versão do filho resolvida na chamada (versão ativa); `flowVersion` opcional no node para fixar.
- **Grafo (§17.12):** DAG estrito, sem ciclos; reprocesso vira nova execução ou sub-flow.
- **Retenção (§17.5/17.8):** configurável por tipo, desligada por padrão (padrão de `app.retention.idempotency`); prazos por ambiente.
- **Primeiro caso de uso (§17.1):** onboarding/KYC PJ (CNPJ) com aprovação humana na divergência.
- **Abertas:** SLO de latência e volume de pico (§17.3/17.4); broker e padrão corporativo de autenticação/eventos (§17.10/17.11).
- **Fechadas (projeto de estudo):** não há broker nem padrão corporativo de autenticação/eventos; mantém API key por tenant, Micrometer e polling. SLO provisório (sem valor contratual), do baseline em `loadtest/REPORT.md`: fluxo leve com p95 < 120 ms a 64 clientes, vazão ≥ 500 req/s na máquina de referência, 0 erros. Revisar quando houver caso real.
