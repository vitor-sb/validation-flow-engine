# PRD: Organização de pastas (persistência e application/execution)

> Executar somente depois que a migração JPA (`prd-migracao-jpa.md`) terminar, porque as duas mexem nos mesmos arquivos.
> Só movimentação: nenhuma lógica muda.

## 1. Introdução

Depois da migração JPA, `adapter/out/postgres` mistura entidades, interfaces Spring Data, mappers e adapters.
`application/execution` tem 13 arquivos soltos que cumprem papéis diferentes (execução de nodes, histórico, idempotência, recuperação).
Esta frente agrupa por papel, seguindo o que já foi feito com `application/flow`, `application/validator` e os `ports`.

## 2. Objetivos

- Achar um arquivo pelo papel dele, sem abrir uma pasta com 15 itens.
- Entidades JPA continuam em `adapter/out/postgres` (nunca em `domain`): são o modelo da tabela, e os tipos de domínio são records imutáveis.

## 3. Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes
- [ ] `openapi.yaml`, migrations e JSON persistido sem diferença
- [ ] Somente `git mv`, ajuste de `package`/`import` e visibilidade mínima para compilar (`public` onde o tipo passou a ser usado de outro pacote); nenhuma linha de lógica alterada
- [ ] Testes movidos para o mesmo pacote da classe que testam
- [ ] Regras do ArchUnit existentes passam sem enfraquecer

## 4. User Stories

### US-001: Separar a persistência em entity, repository e mapper
**Critérios:**
- [ ] `adapter/out/postgres/entity`: `FlowDefinitionEntity`, `FlowExecutionEntity`, `NodeExecutionEntity`, `ExecutionAuditLogEntity`, `IdempotencyKeyEntity`, `AssignedIdEntity`
- [ ] `adapter/out/postgres/repository`: interfaces Spring Data (`*JpaRepository`) e os adapters `JpaFlowRepository`, `JpaExecutionRepository`, `JpaIdempotencyRepository`
- [ ] `adapter/out/postgres/mapper`: `FlowDefinitionMapper`, `ExecutionMapper`
- [ ] Nenhuma classe solta na raiz de `adapter/out/postgres`, exceto o que não se encaixar, com uma linha em `progress.txt` explicando
- [ ] Configuração de `@EntityScan`/`@EnableJpaRepositories`, se existir, aponta para os pacotes novos
- [ ] Regra ArchUnit: `entity` só é usada dentro de `adapter.out.postgres`

### US-002: Separar application/execution por papel
**Critérios:**
- [ ] `ExecutionService` permanece em `application/execution`
- [ ] `application/execution/node`: `NodeHandler`, `NodeHandlers`, `NodeStep`, `NodeConfig`, `ValidatorRunner`, `SubFlowRunner`
- [ ] `application/execution/history`: `ExecutionRecorder`
- [ ] `application/execution/idempotency`: `IdempotentExecutionService`, `IdempotencyRetentionService`
- [ ] `application/execution/recovery`: `RecoveryService`
- [ ] `Maps` fica junto de quem mais o usa (conferir com `grep` antes de mover)
- [ ] `ports` não muda
- [ ] Controllers, `FlowConfig`, `RecoveryConfig` e `RetentionConfig` com imports atualizados
- [ ] Sem ciclo entre os subpacotes novos (verificado pela regra de ciclos do ArchUnit)

## 5. Non-Goals

- Mover entidades para `domain`.
- Dividir `application/flow` (tem poucos arquivos).
- Qualquer mudança de lógica ou de contrato.
