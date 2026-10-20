# PRD: Migração dos repositórios de JDBC para JPA (Spring Data)

> Motivação: legibilidade. Hoje `JdbcFlowRepository` (146 linhas) e `JdbcExecutionRepository` (172 linhas) montam SQL em strings,
> convertem JSON à mão (`?::jsonb`, `json.writeValueAsString`) e mapeiam `ResultSet` coluna a coluna.
> Não é uma mudança de performance: o teste de carga (`loadtest/REPORT.md`) mostra o banco ocioso (≤0,5% de CPU) e não indica o acesso a dados como limite.
> Sem mudança funcional: API, `openapi.yaml`, schema do banco, JSON gravado e códigos de erro ficam idênticos.

## 1. Introdução / Visão geral

O MVP e o refactor de pacotes estão concluídos. Os ports `FlowRepository` e `ExecutionRepository` são implementados por classes JDBC com SQL
em texto. Esta frente troca essas implementações por entidades JPA e interfaces Spring Data, mantendo os ports, o domínio e a camada
`application` intocados.

Onde JPA melhora a leitura: CRUD simples, buscas por tenant e id, listagem paginada e mapeamento de colunas.
Onde NÃO melhora e permanece SQL nativo (em `@Query(nativeQuery = true)`): claim de idempotência (`INSERT ... ON CONFLICT DO UPDATE ... WHERE`),
limpezas por `locked_until < now()` e a atualização com `lock_version`.

## 2. Objetivos

- Repositórios sem SQL em texto, exceto as consultas nativas listadas acima.
- Entidades JPA somente em `adapter/out/postgres`; `domain` e `application` sem `jakarta.persistence`.
- Flyway continua dono do schema; Hibernate apenas valida (`ddl-auto: validate`).
- Zero mudança de comportamento observável e throughput do teste de carga sem queda relevante.

## 3. Regras para todas as stories (critérios fixos)

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes existentes
- [ ] `openapi.yaml` e migrations (`V1`, `V2`) sem diferença
- [ ] JSON gravado em `flow_definition`, `flow_execution`, `node_execution` e `execution_audit_log` equivalente ao anterior (mesmas chaves, `null` vs ausente preservado)
- [ ] Toda query filtra por `tenant_id`; testes de isolamento entre tenants continuam passando
- [ ] Nenhum método com mais de 40 linhas

## 4. User Stories

### US-001: Fundação JPA e prova de mapeamento JSONB
**Descrição:** Como mantenedor, quero provar que o mapeamento JSONB funciona com a stack atual antes de migrar os repositórios.
**Critérios:**
- [ ] `spring.jpa.hibernate.ddl-auto=validate` e `spring.jpa.open-in-view=false` em `application.yaml`
- [ ] Uma entidade de teste/spike grava e lê `Map<String,Object>` e `List<InputField>` em coluna `jsonb` e o JSON lido é igual ao gravado (chaves, tipos, `null`)
- [ ] Decisão registrada em `progress.txt`: mapeamento nativo do Hibernate (`@JdbcTypeCode(SqlTypes.JSON)`) ou `AttributeConverter` com o `JsonMapper` atual (Jackson 3, pacote `tools.jackson`)
- [ ] Spike removido ou promovido a base reutilizável; nenhum repositório de produção alterado
- [ ] Risco a verificar: suporte do Hibernate ao Jackson 3; se não houver, usar o `AttributeConverter` com `@ColumnTransformer(write = "?::jsonb")`

### US-002: Migrar FlowRepository para JPA
**Critérios:**
- [ ] `FlowDefinitionEntity` (implementa `Persistable<UUID>` para evitar `SELECT` antes do `INSERT` quando o id é gerado pela aplicação), mapper entidade ↔ `FlowDefinition` e interface Spring Data, todos em `adapter/out/postgres`
- [ ] `JpaFlowRepository` implementa `FlowRepository`: `nextVersion`, `save`, `findById`, `findActive` (máx. 2), `list` com filtros nulos ignorados e ordem `created_at DESC, flow_key, version DESC`, `updateDraft`, `archive`
- [ ] `activate` com `@Transactional`: arquiva a versão ativa do mesmo seletor e ativa o DRAFT; se não for DRAFT, a transação é revertida e retorna `false`
- [ ] Violação do índice único parcial na ativação concorrente continua resultando em 409 `CONFLICT` (a tradução de exceção do JPA pode vir como `DataIntegrityViolationException` e não `DuplicateKeyException`: ajustar o handler e cobrir com teste)
- [ ] `JdbcFlowRepository` removido; `FlowLifecycleTest` (incluindo o teste de concorrência) e `JdbcFlowRepositoryTest` passam, adaptados apenas no que dependia da classe JDBC

### US-003: Separar o port de idempotência do ExecutionRepository (ISP)
**Descrição:** Como mantenedor, quero que o port de execução não misture chaves de idempotência, para migrar cada parte em separado.
**Critérios:**
- [ ] Novo port `IdempotencyRepository` em `application/execution/ports` com `claimIdempotency`, `findIdempotency`, `completeIdempotency`, `releaseIdempotency`, `deleteExpiredOrphanIdempotency`, `deleteIdempotencyCreatedBefore` e o record `IdempotencyClaim`
- [ ] `ExecutionRepository` fica só com execução, histórico e auditoria
- [ ] `ExecutionService`/idempotência, `RecoveryService` e `IdempotencyRetentionService` dependem do port correto
- [ ] Mudança apenas estrutural: `JdbcExecutionRepository` pode implementar os dois ports até as próximas stories

### US-004: Migrar ExecutionRepository para JPA
**Critérios:**
- [ ] Entidades `FlowExecutionEntity`, `NodeExecutionEntity` e `ExecutionAuditLogEntity` (todas `Persistable<UUID>`), mappers e interfaces Spring Data
- [ ] `insert`, `findById`, `list` (mais novo primeiro, desempate por `id`), `count`, `recordNode`, `findNodes` (ordem `started_at, completed_at, attempt`), `audit` e `findRunningStartedBefore`
- [ ] `update` preserva a semântica atual: retorna `false` quando `lock_version` não confere e incrementa o campo; implementado com `@Modifying` e `WHERE tenant_id = ? AND id = ? AND lock_version = ?` (não trocar por `@Version` sem teste de equivalência)
- [ ] `ExecutionTest`, `ExecutionHttpTest` e `credentialsNeverReachAnyTableOrLog` passam sem alteração de asserções

### US-005: Migrar IdempotencyRepository para JPA com consultas nativas
**Critérios:**
- [ ] Entidade `IdempotencyKeyEntity` com chave composta (`tenant_id`, `idempotency_key`)
- [ ] `claimIdempotency` mantém o `INSERT ... ON CONFLICT DO UPDATE ... WHERE execution_id IS NULL AND locked_until < now()` como `@Modifying @Query(nativeQuery = true)`; um só vencedor sob concorrência
- [ ] `release`, `complete`, `deleteExpiredOrphan` e `deleteCreatedBefore` com as mesmas regras (sem apagar reserva viva)
- [ ] Testes de idempotência, takeover de reserva expirada, recuperação e retenção passam sem alteração

### US-006: Remover o JDBC e travar a arquitetura
**Critérios:**
- [ ] `JdbcExecutionRepository` e qualquer uso de `JdbcTemplate` nos repositórios removidos
- [ ] Testes sem banco (`@SpringBootTest` que excluem DataSource/Hibernate) também excluem a autoconfiguração de repositórios JPA; nenhum teste fica dependente de classe removida
- [ ] Regra ArchUnit: `jakarta.persistence` e `org.springframework.data` só em `adapter.out.postgres`
- [ ] Mensagem "Found 0 JPA repository interfaces" desaparece da subida
- [ ] `tasks/coverage.md` e `progress.txt` registram as decisões (mapeamento JSON, tradução de exceção, `Persistable`)

### US-007: Medir o impacto no desempenho
**Critérios:**
- [ ] `loadtest/run.sh` executado nos mesmos níveis de concorrência (8, 32, 64) e o resultado acrescentado ao `loadtest/REPORT.md` ao lado da linha de base JDBC (≈665 req/s, p95 ≈107 ms a 64)
- [ ] Queda de throughput acima de 10% é registrada como achado, com a causa provável (por exemplo, `SELECT` extra antes do `INSERT`, flush ou mapeamento JSON) e a correção aplicada ou a decisão de aceitar
- [ ] Contagem de statements por execução (`pg_stat_statements` ou log SQL em ambiente local) comparada com a versão JDBC

## 5. Non-Goals

- Alterar o schema, as migrations ou o contrato HTTP.
- Trocar o executor, o domínio ou os ports além da separação da US-003.
- Cache de segundo nível, batch de inserts ou gravação assíncrona de histórico (tema de escala do v0.1).
- Usar `ddl-auto` para gerar schema.

## 6. Riscos

- **JSONB com Jackson 3:** pode não haver suporte nativo do Hibernate; mitigado pela US-001.
- **Insert com id gerado na aplicação:** sem `Persistable`, o Spring Data faz um `SELECT` antes de cada `INSERT` (regressão de desempenho).
- **Tradução de exceções:** `DuplicateKeyException` pode virar `DataIntegrityViolationException` e causar 500 no lugar de 409 (FR-24).
- **Reflexão do Hibernate nos recordes do domínio:** por isso as entidades são separadas dos records do domínio, com mapper.
- **Testes sem banco:** autoconfigurações excluídas hoje precisam incluir os repositórios JPA.
