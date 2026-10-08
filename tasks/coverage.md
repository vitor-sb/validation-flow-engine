# Rastreabilidade: v0.1 (objetivo principal) x implementação

Referência: `validation-flow-engine-v0.1.md` (seção 15) e `prd-validation-flow-engine.md` (FR-1..FR-24).
Atualizar sempre que um PRD novo for fechado. Status: OK | LACUNA | ADIADO (fase X) | NÃO VERIFICADO.

## Lacunas do MVP

### GAP-1: Observabilidade (critério 11, FR-19): OK
- Contadores `validation.node.timeout|error|retry|rejected`, contador `validation.execution` (tag `status`) e timers `validation.execution.duration` e `validation.node.duration` (p50/p95/p99), via porta `ExecutionMetrics`.
- `/actuator/metrics` exposto e protegido pela autenticação existente (`management.endpoints.web.exposure.include`).
- Testes: `ExecutionTest.executionCounterAndTimersTrackCompletedAndFailed` e `ExecutionHttpTest.metricsAreExposedBehindAuthentication`.

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

fork/join, ASYNC_VALIDATION, EXTERNAL_CALL, callbacks/polling, retomada (`/resume`), dead-letter,
HUMAN_APPROVAL, cache de provedores, particionamento, broker, réplicas de leitura, projeções,
aprovação em dois níveis para ativação.

## Persistência: decisões da migração JDBC → JPA

- **Mapeamento JSON:** `@JdbcTypeCode(SqlTypes.JSON)` nativo do Hibernate (Jackson 3 funciona); sem `AttributeConverter`. JSON nulo vira SQL NULL (antes, literal jsonb `null`); a leitura de domínio é idêntica.
- **Tradução de exceções:** violação de unique chega como `DataIntegrityViolationException`; `RestExceptionHandler` devolve 409 só quando SQLState 23505 está na cadeia, senão 500. `DuplicateKeyException` segue mapeada para 409.
- **Persistable:** entidades com id gerado pela aplicação estendem `AssignedIdEntity` (`Persistable<UUID>`), evitando SELECT antes do INSERT. `IdempotencyKeyEntity` usa `@EmbeddedId` e nunca é salva via `save`.
- **Lock otimista:** `flow_execution.update` continua `@Modifying` com `WHERE lock_version = ?`, sem `@Version`.
- **Idempotência:** o claim atômico permanece em SQL nativo (`INSERT ... ON CONFLICT DO UPDATE ... WHERE`).
- **Arquitetura:** regra ArchUnit restringe `jakarta.persistence` e `org.springframework.data` a `adapter.out.postgres`.
