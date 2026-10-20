# PRD: Refatoração Clean Code / SOLID + fechamento do GAP-1 (métricas)

> Terceira frente do projeto. Não entrega funcionalidade nova, exceto a US-R08 (GAP-1, ver `coverage.md`).
> Referência do objetivo principal: `validation-flow-engine-v0.1.md`. O MVP está concluído (US-001..US-032).

## 1. Introdução

O MVP funciona e tem boa cobertura, mas o código de execução concentrou responsabilidades. Auditoria feita sobre
`application/ExecutionService.java` (527 linhas, 7 parâmetros no construtor):

- **SRP:** uma classe cuida de idempotência (claim, hash canônico, espera), orquestração do grafo, retry/backoff,
  pool de timeout, sub-flow, mascaramento de segredos, MDC/log e persistência do histórico.
- **OCP:** `walk` usa `switch` sobre o tipo do node. Um node novo (fork/join, human approval, previstos nas fases 3 e 4 do
  v0.1) obriga a editar o executor.
- **DIP:** a camada `application` importa `io.micrometer.MeterRegistry` direto.
- **Tipagem:** config de node, retryPolicy e snapshot passam como `Map<String,Object>` com casts e `@SuppressWarnings("unchecked")`
  espalhados.
- Não auditados ainda: `GraphValidator` (266 linhas), `FlowService`, controllers e repositórios JDBC. A US-R07 cobre essa auditoria.

## 2. Objetivos

- Pacotes por feature (`flow`, `execution`, `validator`), com ports em subpasta `ports`.
- Cada classe com uma responsabilidade clara; `ExecutionService` vira orquestrador fino.
- Adicionar um novo tipo de node sem editar o laço de execução.
- `application` sem dependência de bibliotecas de infraestrutura.
- Zero mudança de comportamento observável: API, JSON persistido, códigos de erro e `openapi.yaml` idênticos.
- Fechar o GAP-1 (métricas de execução e latência).

## 3. Regras para todas as stories (critérios fixos)

- [ ] `mvn verify` verde, sem remover nem enfraquecer testes existentes
- [ ] `openapi.yaml` sem diferença (exceto onde a story diz o contrário)
- [ ] JSON gravado em `flow_execution`, `node_execution` e `execution_audit_log` idêntico ao anterior
- [ ] Nenhum método novo ou alterado com mais de 40 linhas
- [ ] Sem abstração sem uso: interface só se tiver 2+ implementações ou for porta de camada

## 4. User Stories

### US-R00: Reorganizar pacotes por feature, com ports em subpasta
**Descrição:** Como mantenedor, quero pastas que mostrem o domínio, em vez de 11 arquivos soltos em `application/`. Vem antes das extrações para que as classes novas já nasçam no lugar certo.
**Critérios:**
- [ ] `application/flow` (FlowService, FlowException), `application/execution` (ExecutionService, RecoveryService, IdempotencyRetentionService), `application/validator` (ValidatorRegistry, ValidatorStrategy, ValidatorException), `application/masking` (LogMasker)
- [ ] Ports em `application/flow/ports` (FlowRepository) e `application/execution/ports` (ExecutionRepository)
- [ ] `domain/flow` e `domain/execution` separam as classes de domínio; `config/security` agrupa ApiKey*, SecurityConfig, TenantPrincipal e BodySizeLimitFilter
- [ ] Somente movimentação e ajuste de imports/visibilidade: nenhuma linha de lógica alterada
- [ ] Testes movidos para os mesmos pacotes das classes; regras ArchUnit existentes passam sem enfraquecer
- [ ] `package-info.java` de cada pacote novo com uma linha descrevendo a responsabilidade

### US-R01: Extrair a idempotência do ExecutionService
**Descrição:** Como mantenedor, quero a lógica de `Idempotency-Key` isolada para que o executor não conheça claim, hash e espera.
**Critérios:**
- [ ] Nova classe em `application` com `executeIdempotent`, `hash` e `canonical`; `ExecutionService.execute` continua público
- [ ] `ExecutionController` usa a nova classe; comportamento de replay, 409 e espera de 30s idêntico
- [ ] Os testes de idempotência existentes passam sem alteração

### US-R02: Extrair a execução de validators (retry, backoff, timeout)
**Descrição:** Como mantenedor, quero `runWithRetry`, `call`, `Attempt` e o `timeoutPool` fora do executor.
**Critérios:**
- [ ] Nova classe `ValidatorRunner` em `application` recebe o `ExecutionService` apenas como chamador
- [ ] Contadores de métricas e códigos `NODE_TIMEOUT`, `NODE_REJECTED`, `VALIDATOR_ERROR` inalterados
- [ ] Teste unitário do backoff exponencial com cap sem precisar de Postgres

### US-R03: Extrair o tratamento de SUB_FLOW
**Critérios:**
- [ ] Nova classe `SubFlowRunner` com `subFlow` e `jsonPath`
- [ ] Detecção de ciclo, `maxDepth`, `inputMapping` e `outputMapping` inalterados
- [ ] Testes de sub-flow existentes passam sem alteração

### US-R04: Tipar a configuração dos nodes
**Descrição:** Como mantenedor, quero records tipados no lugar de `Map<String,Object>` com casts.
**Critérios:**
- [ ] Records `RetryPolicy` e `NodeConfig` (timeout, validatorType, retryPolicy, maxDepth, mappings) criados a partir do snapshot
- [ ] Removidos os `@SuppressWarnings("unchecked")` da lógica de execução; `Map` permanece só em `inputData`, `contextData`, `result` e `config` dinâmicos
- [ ] Snapshot gravado no banco continua com o mesmo formato

### US-R05: Um handler por tipo de node (OCP)
**Descrição:** Como mantenedor, quero adicionar um node novo sem editar o laço do executor.
**Critérios:**
- [ ] Interface `NodeHandler` (`type()`, `handle(...)`) com handlers para START, DECISION, VALIDATION, SUB_FLOW e END
- [ ] O laço de execução só resolve o handler por tipo e avalia transições; não há `switch` por tipo
- [ ] Tipo sem handler continua falhando com `UNSUPPORTED_NODE_TYPE`
- [ ] Teste adiciona um handler fake de teste e o executa sem alterar o executor

### US-R06: Extrair registro de histórico e mascaramento
**Critérios:**
- [ ] Classe `ExecutionRecorder` com `recordNode`, auditoria de transições, MDC e log
- [ ] `secrets`, `redact` e `maskObserved` movidos para junto do `LogMasker`
- [ ] `ExecutionTest.credentialsNeverReachAnyTableOrLog` passa sem alteração

### US-R07: Desacoplar métricas da camada application (DIP)
**Critérios:**
- [ ] Porta `ExecutionMetrics` em `application` (métodos por evento: timeout, erro, retry, rejeitado)
- [ ] Adaptador Micrometer em `config`; `application` sem import de `io.micrometer`
- [ ] Regra ArchUnit nova garante a ausência

### US-R08: Fechar o GAP-1, métricas de execução e latência
**Critérios:**
- [ ] Contador `validation.execution` com tag `status` e timer de duração por execução e por node
- [ ] `/actuator/metrics` exposto e protegido pela autenticação existente
- [ ] Teste lê `validation.execution` após uma execução completa e uma falha
- [ ] `coverage.md` atualizado: GAP-1 como OK e critério 11 como OK

### US-R09: Auditar e refatorar GraphValidator, FlowService, controllers e repositórios
**Critérios:**
- [ ] Nenhum método com mais de 40 linhas nem classe com mais de 250 linhas nesses arquivos
- [ ] `GraphValidator` separado em uma regra por unidade, mantendo os mesmos códigos de erro
- [ ] Achados que não couberem na story registrados em `progress.txt` para o próximo ciclo

### US-R10: Travar a arquitetura com ArchUnit
**Critérios:**
- [ ] Regras: sem ciclos entre pacotes, `application` sem bibliotecas de infraestrutura, nomes de ports e handlers por convenção
- [ ] `ExecutionService` com no máximo 4 dependências no construtor

## 5. Non-Goals

- Nova funcionalidade de produto (fases 3 a 5 do v0.1).
- Mudança de contrato HTTP, de schema de banco ou de formato de JSON persistido.
- Troca de framework ou de biblioteca.
