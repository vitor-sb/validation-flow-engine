# PRD: Segunda rodada de SOLID, tipagem e rastreabilidade de logs

> Derivado de auditoria de código de 2026-10-20. Continua `02-prd-refactor-clean-code.md` (as stories R00-R10 já estão feitas; este PRD cobre o que sobrou ou voltou a crescer).
> Não muda contrato HTTP, schema de banco nem formato de JSON persistido. Rodar depois (ou fora de conflito) de `09-prd-pendencias.md`.
> Cada story roda `mvn -B verify` (~8 min, em background).

## Estado atual (verificado no código)

- `application` e `domain` não importam Spring, jakarta nem `adapter`; beans montados em `config/FlowConfig`. Isso deve ser preservado.
- `ExecutionService` com 391 linhas: executa, resume (manual e por provider), continua pai/filho, cancela, consulta e persiste com mascaramento. `resumeAt` e `continueParent` repetem o bloco try/catch/MDC/`recorder.failed`.
- `FlowService` com 270 linhas e 3 construtores (um deles passa `null`). Mistura CRUD de flow com `disabledProviders` e `operationProblems`; este último monta o erro como string `"CODE msg"` e separa com `substring/indexOf`.
- `NodeHandlers` com 256 linhas e 7 handlers numa única classe.
- Grafo e contexto lidos como `Map<String,Object>` com cast em `walk`/`nextNode`.
- `FlowExecution` reconstruído com 17-20 argumentos em `ExecutionService.run` e `save`.
- Logs: 10 chamadas no total. Sem filtro de entrada HTTP, sem `tenantId` no MDC, `correlationId` ausente vira a string `"null"`, `FlowException` não é logada.

## Objetivos

- Nenhuma classe de `application` acima de 250 linhas nem método acima de 40.
- `ExecutionService` com responsabilidade única de orquestrar a execução.
- Toda requisição HTTP, mesmo a que falha antes de criar execução, é rastreável por um id.
- Logs de produção respondem "quem (tenant), qual execução, qual nó, o que aconteceu" sem abrir o banco.

## Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes existentes
- [ ] `openapi.yaml` sem diferença (exceto onde a story disser)
- [ ] JSON em `flow_execution`, `node_execution` e `execution_audit_log` idêntico
- [ ] ArchUnit existente passa sem enfraquecer

## Feature A — Rastreabilidade (começar por aqui)

#### US-L01: Filtro de correlação na entrada HTTP
**Acceptance Criteria:**
- [ ] Filtro em `config/security` (ou `adapter/in/rest`) lê `X-Correlation-Id`/`X-Request-Id`, valida (até 128 chars `[A-Za-z0-9._:-]`), gera UUID se ausente e devolve o header na resposta
- [ ] Coloca `requestId` e `tenantId` (após autenticação) no MDC e limpa só as chaves próprias no `finally`
- [ ] Loga `info` por requisição: método, path (sem query string), status e duração
- [ ] Pattern de log de `application.yaml` inclui `tenantId` e `requestId`
- [ ] Teste: requisição que falha em 404/400 antes da execução gera linha de log com `requestId`

#### US-L02: `correlationId` consistente
**Acceptance Criteria:**
- [ ] `StartRequest.correlationId` ausente passa a usar o `requestId` da US-L01 (nunca a string `"null"`)
- [ ] `ExecutionRecorder.enterMdc` não usa `String.valueOf` em valor nulo
- [ ] `correlationId` gravado em `flow_execution` continua o mesmo quando informado pelo cliente

#### US-L03: Logar erros esperados e eventos de ciclo de vida
**Acceptance Criteria:**
- [ ] `RestExceptionHandler`: `FlowException` e 4xx logam `warn` com `kind`, código e mensagem; 5xx mantém `error` com stack trace
- [ ] `execution started` (flowKey, version), `execution waiting`, `execution resumed`, `execution cancelled`, `execution finished status=...` com duração
- [ ] Logs de provider seguem `chave=valor` já usado (`executionId`, `callId`, `providerKey`, `status`)
- [ ] Nenhum payload sem passar por `LogMasker.mask`

#### US-L04: Teste de não vazamento nos novos logs
**Acceptance Criteria:**
- [ ] `ExecutionTest.credentialsNeverReachAnyTableOrLog` estendido para cobrir as novas linhas (CPF/CNPJ e credenciais não aparecem)

## Feature B — ExecutionService

#### US-S01: Extrair o bloco comum de `resumeAt` e `continueParent`
**Acceptance Criteria:**
- [ ] Um método privado (ou classe `ExecutionScope`) encapsula: `enterMdc` → bloco → catch `RuntimeException` → `recorder.failed` + `ex.fail(EXECUTION_ERROR)` → `restoreMdc`
- [ ] `resumeAt` e `continueParent` não repetem o try/catch/finally
- [ ] Testes de resume e sub-flow passam sem alteração

#### US-S02: Separar resume e cancelamento
**Acceptance Criteria:**
- [ ] `ExecutionResumer` (resume, resumeWithProviderResult, continueParent) e `ExecutionCanceller` (cancel + cascade) extraídos
- [ ] `ExecutionService` mantém `execute`/`run`/`walk` e delega; assinaturas públicas usadas por controllers e `IdempotentExecutionService` preservadas
- [ ] Consultas (`get`, `nodes`, `list`, `count`) saem para `ExecutionQueries` ou ficam atrás de uma fachada fina
- [ ] `ExecutionService` ≤ 200 linhas e ≤ 4 dependências no construtor (regra ArchUnit já existente)

#### US-S03: `FlowExecution` com `withX`
**Acceptance Criteria:**
- [ ] Métodos `withInputData`, `withContext`, `withResult`, `withErrorInfo`, `withLockVersion` (ou builder) no record/classe
- [ ] Construtor de 17-20 argumentos deixa de ser chamado fora do domínio
- [ ] `save` e `run` ficam com menos de 15 linhas cada

## Feature C — FlowService e provider

#### US-S04: Extrair validação de provider/operação de `FlowService`
**Acceptance Criteria:**
- [ ] Nova classe `FlowProviderValidator` com `disabledProviders` e `operationProblems`
- [ ] Erro tipado (`record GraphError(code, nodeId, message)` retornado direto) no lugar de `"CODE msg"` + `substring`
- [ ] `FlowService` com um único construtor, sem `null` como dependência; `FlowConfig` ajustado
- [ ] Mesmos códigos de erro (`PROVIDER_DISABLED`, `OPERATION_REQUIRED`, `UNKNOWN_OPERATION`, `MISSING_PARAMETER`, `UNKNOWN_PARAMETER`)
- [ ] `FlowService` ≤ 200 linhas

#### US-S05: Um arquivo por `NodeHandler`
**Acceptance Criteria:**
- [ ] Cada handler (`Start`, `Decision`, `Validation`, `SubFlow`, `HumanApproval`, `ExternalCall`, `End`) em arquivo próprio no pacote `node` (ou `node/handlers`)
- [ ] `NodeHandlers.defaults(...)` mantido como fábrica
- [ ] Sem mudança de comportamento; testes passam

## Feature D — Tipagem do grafo

#### US-T01: Records para nó e transição
**Acceptance Criteria:**
- [ ] `GraphNode(id, type, config, transitions)` e `Transition(to, label, condition)` criados a partir do snapshot uma única vez, na entrada da execução
- [ ] `walk` e `nextNode` deixam de usar `Maps.of`/cast de `Map`
- [ ] Snapshot gravado no banco no mesmo formato JSON
- [ ] `@SuppressWarnings("unchecked")` removidos de `ExecutionService`

#### US-T02: Tipar o contexto de execução
**Acceptance Criteria:**
- [ ] `ExecutionContext` (inputData, nodes/outputs) com métodos de acesso, usado em `ConditionEvaluator` e handlers
- [ ] `Map<String,Object>` permanece apenas nas fronteiras dinâmicas (inputData, outputs de validators, JSON persistido)

## Feature E — Configuração e estilo

#### US-C01: Limpar `FlowConfig`
**Acceptance Criteria:**
- [ ] Imports no lugar de nomes totalmente qualificados (`java.util.Map`, `@Value`)
- [ ] Mapa `byKey` de providers criado uma única vez (bean `ProviderRegistry` ou método privado)
- [ ] `@Value` agrupados em `@ConfigurationProperties` (limites, TTLs, lease)

#### US-C02: Formatter e imports
**Acceptance Criteria:**
- [ ] Plugin de formatação (`spring-javaformat` já em tabs, ou equivalente) rodando no `verify`
- [ ] Imports ordenados; sem import não usado

## Non-Goals

- Troca de modelo de execução, de banco ou de biblioteca.
- Logging estruturado em JSON (avaliar depois da Feature A).
- Novos tipos de nó ou provedores (cobertos por `09-prd-pendencias.md`).

## Ordem sugerida

Feature A → S01 → S02 → S03 → S04 → S05 → T01 → T02 → C01 → C02.
