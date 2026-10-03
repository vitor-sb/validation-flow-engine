# PRD: Validation Flow Engine (MVP)

> Derivado de `novo projeto/validation-flow-engine.md` (v0.1). Escopo: **somente MVP** (Fases 0–2 do roadmap + sub-flow simples). Caso de uso: **genérico, sem fluxo de negócio fixo**. Backend apenas.

## 1. Introdução / Visão geral

O Validation Flow Engine é um backend para **definir, versionar e executar fluxos de validação antifraude** como grafos (nodes + transições condicionais), sem alterar o código do motor a cada nova regra.

Hoje cada processo (crédito, onboarding, cartão etc.) reimplementa regras, integrações, retries e auditoria. Isso gera duplicação, mudanças que exigem deploy, baixa auditabilidade e tratamento inconsistente de timeout/indisponibilidade.

O MVP entrega o núcleo: catálogo de fluxos versionados, validação estrutural do grafo, execução com histórico por node, validators plugáveis (Strategy/Registry), idempotência, multi-tenancy e um sub-flow simples.

**Stack:** Java 17+, Spring Boot, PostgreSQL (JSONB). Arquitetura hexagonal, modular monolith.

## 2. Objetivos

- Criar, validar, versionar, ativar e arquivar fluxos via API.
- Resolver o fluxo ativo por `(tenantId, userType, context)` ou `flowKey`.
- Executar grafos com sequência e ramificação condicional de forma determinística.
- Adicionar um novo validator sem alterar o executor.
- Garantir que cada execução fique presa à versão do fluxo usada no início.
- Isolar totalmente dados de tenants diferentes.
- Registrar histórico consultável por node (entrada, saída, erro, transições avaliadas).
- Medir a capacidade com um teste de carga inicial antes de assumir qualquer meta de escala.

## 3. User Stories

> Todas as stories incluem "build e testes passam" (`mvn verify` ou equivalente). Não há UI no MVP.

### US-001: Esqueleto hexagonal do projeto
**Description:** Como desenvolvedor, quero a estrutura de pacotes e as regras de dependência definidas para que o domínio fique isolado de framework e banco.

**Acceptance Criteria:**
- [ ] Projeto Spring Boot (Java 17+) com pacotes `domain`, `application`, `adapter/in/rest`, `adapter/out/postgres`, `config`
- [ ] Teste de arquitetura (ex.: ArchUnit) falha se `domain` depender de outra camada ou `application` depender de `adapter`
- [ ] Endpoints `/actuator/health` (liveness/readiness) e `/api/v1/health` respondem 200
- [ ] Build e testes passam

### US-002: Schema PostgreSQL inicial
**Description:** Como desenvolvedor, quero as tabelas do MVP versionadas por migration para persistir fluxos, execuções e histórico.

**Acceptance Criteria:**
- [ ] Migrations (Flyway ou Liquibase) criam `flow_definition`, `flow_execution`, `node_execution`, `execution_audit_log`, `idempotency_key`
- [ ] Campos variáveis (`graph_definition`, `metadata`, `input_data`, `context_data`, `result`, `error_info`, snapshots) são `JSONB`
- [ ] `UNIQUE(tenant_id, flow_key, version)` em `flow_definition`
- [ ] Índice único parcial garante no máximo 1 versão `ACTIVE` por `(tenant_id, user_type, context)`
- [ ] Todas as tabelas possuem `tenant_id`
- [ ] Migrations aplicam em banco vazio (teste com Testcontainers)
- [ ] Build e testes passam

### US-003: Autenticação com tenant e escopos
**Description:** Como sistema consumidor, quero me autenticar com uma credencial ligada a um tenant para que meus dados fiquem isolados e minhas permissões sejam limitadas.

**Acceptance Criteria:**
- [ ] `tenantId` é resolvido da credencial (API key/token), nunca do corpo da requisição
- [ ] Escopos suportados: `validation:execute`, `validation:read`, `flow:read`, `flow:write`, `flow:activate`
- [ ] Credencial com apenas `validation:execute` recebe 403 em `POST /flows` e `PATCH /flows/{id}/activate`
- [ ] Credencial sem credencial válida recebe 401
- [ ] Todas as consultas ao banco filtram por `tenant_id` do contexto autenticado
- [ ] Build e testes passam

### US-004: Criar fluxo em DRAFT
**Description:** Como analista, quero cadastrar um fluxo em rascunho para depois validá-lo e ativá-lo.

**Acceptance Criteria:**
- [ ] `POST /api/v1/flows` aceita `flowKey`, `userType`, `context`, `displayName`, `description`, `graphDefinition`, `metadata`, contrato de entrada (campos obrigatórios/opcionais com tipos)
- [ ] Fluxo criado com `status=DRAFT`, `version` gerada, `createdBy`/`createdAt` preenchidos
- [ ] Campos `@NotBlank`/`@NotNull` inválidos retornam 400 com `ErrorResponse`
- [ ] Dois tenants podem criar a mesma `flowKey` sem colisão
- [ ] Build e testes passam

### US-005: Validação estrutural do grafo
**Description:** Como analista, quero validar o grafo antes de ativar para não publicar um fluxo quebrado.

**Acceptance Criteria:**
- [ ] `POST /api/v1/flows/validate` valida sem persistir e retorna lista de erros
- [ ] Detecta: ausência de `startNodeId`/node `START`, nenhum caminho até `END`, edge para node inexistente, ciclos (quando DAG), nodes inalcançáveis
- [ ] Detecta: `validatorType` inexistente no registry, timeout/retry inválidos, condição ou mapping inválido, profundidade de sub-flow acima do limite
- [ ] Um teste unitário por regra acima (grafo válido passa, grafo inválido falha com código de erro específico)
- [ ] Build e testes passam

### US-006: Versionamento e ativação atômica
**Description:** Como analista, quero ativar uma versão sem afetar execuções em andamento e sem editar versões ativas.

**Acceptance Criteria:**
- [ ] `PUT /flows/{flowId}` em fluxo `ACTIVE` cria nova versão `DRAFT`; a versão ativa não é alterada
- [ ] `PATCH /flows/{flowId}/activate` roda a validação estrutural e rejeita (422) se inválida
- [ ] Ativação arquiva a versão `ACTIVE` anterior do mesmo seletor na mesma transação
- [ ] Duas ativações concorrentes do mesmo seletor: apenas uma tem sucesso (teste de concorrência)
- [ ] `PATCH /flows/{flowId}/archive` arquiva; versões arquivadas continuam consultáveis via `GET`
- [ ] Grafo de versão ativada é imutável (tentativa de alteração retorna 409)
- [ ] Build e testes passam

### US-007: Consulta e listagem de fluxos
**Description:** Como analista, quero listar e consultar fluxos para auditar o que está publicado.

**Acceptance Criteria:**
- [ ] `GET /flows` com paginação e filtros (`flowKey`, `status`, `userType`, `context`)
- [ ] `GET /flows/{flowId}` retorna definição completa
- [ ] Fluxo de outro tenant retorna 404
- [ ] Build e testes passam

### US-008: Resolução do fluxo ativo
**Description:** Como engine, quero resolver a versão ativa a partir de `flowKey` ou de `(tenantId, userType, context)`.

**Acceptance Criteria:**
- [ ] Com `flowKey` informado, resolve a versão `ACTIVE` dessa chave no tenant
- [ ] Sem `flowKey`, resolve por `(tenantId, userType, context)`
- [ ] Nenhuma versão ativa encontrada: erro 404 com código `FLOW_NOT_FOUND`
- [ ] Mais de uma versão ativa para o seletor: erro de configuração inválida, sem desempate por data ou ordem do banco
- [ ] Grupos, papéis e canais NÃO participam da seleção (apenas das conditions)
- [ ] Build e testes passam

### US-009: Avaliador de condições (Java puro)
**Description:** Como analista, quero condições estruturadas sobre o payload para decidir o caminho do fluxo de forma segura e determinística.

**Acceptance Criteria:**
- [ ] Suporta `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_OR_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUALS`, `CONTAINS`, `IN`, `EXISTS`, `AND`, `OR`, `NOT`
- [ ] `CONTAINS` exige coleção; `IN` compara valor com lista permitida
- [ ] Campo ausente, nulo ou tipo incompatível resulta em `false`, nunca em `true` silencioso (teste por operador)
- [ ] Nenhuma expressão textual livre é executada (sem SpEL/script)
- [ ] Cada avaliação retorna resultado + valor observado para registro no histórico
- [ ] Build e testes passam

### US-010: Validator Registry (Strategy)
**Description:** Como desenvolvedor, quero registrar novos validators por chave estável sem tocar no executor.

**Acceptance Criteria:**
- [ ] Interface `ValidatorStrategy { String key(); ValidationResult execute(ValidationInput) }` na camada `application`
- [ ] Registry descobre beans `ValidatorStrategy` automaticamente e rejeita chaves duplicadas na inicialização
- [ ] Teste adiciona um validator fake novo e executa um fluxo com ele sem alterar código do executor
- [ ] `GET /node-types` lista validators; `GET /node-types/{key}` retorna schema/capabilities
- [ ] Build e testes passam

### US-011: Executor de grafo sequencial e condicional
**Description:** Como sistema consumidor, quero iniciar uma validação e obter a decisão após o grafo ser percorrido.

**Acceptance Criteria:**
- [ ] `POST /api/v1/executions` com `userType`, `context`, `inputData` (e `flowKey`/`correlationId` opcionais) cria a execução e retorna `executionId`, `flowKey`, `flowVersion`, `status`, `startedAt`
- [ ] Contrato de entrada do fluxo é validado antes de iniciar o grafo; campo obrigatório ausente retorna 400
- [ ] Executa nodes `START`, `VALIDATION`, `DECISION`, `END`; transições avaliadas na ordem, seguindo a primeira cuja condição é verdadeira
- [ ] Sem transição correspondente: falha com erro explícito `NO_MATCHING_TRANSITION` (execução `FAILED`)
- [ ] Execução usa snapshot da versão resolvida; ativar nova versão durante a execução não a afeta (teste)
- [ ] Status válidos: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `TIMED_OUT`, `CANCELLED`; transições de estado controladas pelo domínio com locking otimista
- [ ] Contexto isolado por execução
- [ ] No MVP a resposta é **síncrona**: o `POST` retorna a decisão final (`COMPLETED`/`FAILED`) no mesmo request; o modelo de resposta (`executionId` + `status`) já permite, no futuro, responder `RUNNING` e notificar o resultado por callback (ver Non-Goals e Questões em aberto)
- [ ] Build e testes passam

### US-012: Timeout e retry por node
**Description:** Como analista, quero configurar timeout e retry por node para tratar indisponibilidade de forma consistente.

**Acceptance Criteria:**
- [ ] `config.timeout` (ISO-8601, ex.: `PT30S`) é aplicado; estouro marca a tentativa como `TIMED_OUT`
- [ ] `config.retryPolicy` (`maxAttempts`, `backoff` fixo/exponencial) re-tenta apenas erros marcados `retryable`
- [ ] Cada tentativa gera um registro em `node_execution` com `attempt` incrementado
- [ ] Esgotadas as tentativas, a execução termina `FAILED` com `ErrorResponse(code, retryable, details)`
- [ ] Timeout, retry e erro expostos como métricas (contadores por validator)
- [ ] Build e testes passam

### US-013: Histórico por node e consulta de execução
**Description:** Como auditor, quero saber o que ocorreu em cada execução e por que a decisão foi tomada.

**Acceptance Criteria:**
- [ ] `node_execution` guarda `input_snapshot`, `output_data`, `error_info`, `attempt`, `started_at`, `completed_at`
- [ ] Transições e conditions avaliadas (com valor observado) ficam registradas em `execution_audit_log`
- [ ] `GET /executions/{id}` retorna status, resultado, `tenantId`-scoped; `GET /executions/{id}/nodes` retorna histórico; `GET /executions` lista com paginação
- [ ] Execução de outro tenant retorna 404
- [ ] Dados sensíveis (documentos, tokens) mascarados em logs técnicos; logs trazem `correlationId`, `executionId`, `flowKey`, `flowVersion`, `nodeId`, `status`
- [ ] Build e testes passam

### US-014: Idempotência no início de execução
**Description:** Como sistema consumidor, quero reenviar `POST /executions` com a mesma `Idempotency-Key` sem duplicar a operação.

**Acceptance Criteria:**
- [ ] Header `Idempotency-Key` aceito; escopo da chave = tenant
- [ ] Repetição com mesma chave e mesmo payload retorna a execução original
- [ ] Mesma chave com payload diferente retorna 409
- [ ] Duas requisições simultâneas com a mesma chave criam apenas uma execução (teste de concorrência)
- [ ] Build e testes passam

### US-015: Sub-flow simples
**Description:** Como analista, quero que um fluxo chame outro fluxo para reutilizar validações comuns.

**Acceptance Criteria:**
- [ ] Node `SUB_FLOW` aceita `flowKey` do filho, `inputMapping` e `outputMapping` (JSONPath), `onFailure` (`FAIL_PARENT`), `maxDepth`
- [ ] Filho executa como `flow_execution` com `parent_execution_id`/`parent_node_id`; usa a versão ativa no momento da chamada (ver Questões em aberto)
- [ ] Filho recebe só o subconjunto mapeado e não altera o contexto do pai; o pai recebe só o `outputMapping`
- [ ] Profundidade acima de `maxDepth` ou composição cíclica (A→B→A) é rejeitada na ativação e em runtime
- [ ] Build e testes passam

### US-016: Multi-tenancy comprovada
**Description:** Como responsável de segurança, quero evidência de que tenants não se enxergam.

**Acceptance Criteria:**
- [ ] Teste de integração: tenant A e B com mesma `flowKey`, fluxos e execuções distintas
- [ ] Teste: tenant A não lê, ativa, arquiva nem executa fluxo do tenant B (404)
- [ ] Execução registra `tenantId`, `flowVersion` e as conditions/dados de contexto usados
- [ ] Build e testes passam

### US-017: Grupos de documentos na configuração do fluxo
**Description:** Como analista, quero declarar grupos de documentos (obrigatórios, alternativos, condicionais) no fluxo para pedir documentos diferentes conforme o payload.

**Acceptance Criteria:**
- [ ] Grupo declarado em `config.params` do node ou no grafo, versionado no snapshot (sem cadastro global)
- [ ] Suporta itens obrigatórios, alternativos (um entre N) e condicionais (via condition)
- [ ] Condition `inputData.groups CONTAINS "X"` seleciona o grupo; resultado e documentos efetivamente aplicados são registrados na execução
- [ ] Build e testes passam

### US-018: Teste de carga inicial
**Description:** Como time, quero medir a capacidade real antes de assumir metas de escala.

**Acceptance Criteria:**
- [ ] Script de carga reproduzível (ex.: k6/Gatling) executando fluxo leve com validator fake
- [ ] Relatório com throughput, latência p50/p95/p99 e uso de banco em uma máquina de referência documentada
- [ ] Nenhuma meta de volume (35–50 mi/dia, 800–1.500/s) é tratada como requisito; resultado vira linha de base
- [ ] Build e testes passam

### US-019: Documentação OpenAPI (`openapi.yaml`)
**Description:** Como consumidor e como desenvolvedor, quero um contrato OpenAPI versionado para integrar e gerar clientes sem ler o código.

**Acceptance Criteria:**
- [ ] Arquivo `openapi.yaml` (OpenAPI 3.x) na raiz do módulo/repositório documenta todos os endpoints do MVP: gestão de fluxos, execuções, `/node-types`, health
- [ ] Cada endpoint tem descrição, parâmetros (incl. header `Idempotency-Key`), schemas de request/response, códigos de erro (400, 401, 403, 404, 409, 422) com `ErrorResponse`, exemplos e escopo de segurança exigido
- [ ] Schemas de `inputData`, `contextData`, `result` e `config` documentados como objetos dinâmicos com exemplo
- [ ] Execution API e Flow Management API separadas por tags
- [ ] `openapi.yaml` é **gerado a partir do código** (ex.: springdoc-openapi com anotações nos controllers/DTOs) e commitado; o build falha se o arquivo versionado estiver desatualizado em relação ao gerado
- [ ] O arquivo gerado é validado no build (ex.: linter Spectral/swagger-parser)
- [ ] Build e testes passam

## 4. Requisitos funcionais

- **FR-1:** O sistema deve cadastrar fluxos em `DRAFT` com tenant, `flowKey`, `userType`, `context`, contrato de entrada, grafo e metadados.
- **FR-2:** O sistema deve validar estruturalmente o grafo antes da ativação (início, caminho até `END`, referências, ciclos, inalcançáveis, validators, timeout/retry, conditions, mappings, profundidade).
- **FR-3:** Fluxos ativos não podem ser editados de forma destrutiva; alterações geram nova versão.
- **FR-4:** Deve existir no máximo uma versão `ACTIVE` por `(tenantId, userType, context)`; a ativação é atômica.
- **FR-5:** Execuções em andamento permanecem vinculadas ao snapshot da versão inicial.
- **FR-6:** A seleção do fluxo usa `flowKey` explícito ou `(tenantId, userType, context)`; empates são erro de configuração.
- **FR-7:** O engine deve aceitar `inputData` genérico, sem campos de negócio reservados, validado contra o contrato de entrada do fluxo.
- **FR-8:** Condições são estruturadas e suportam os 12 operadores do MVP; ausente/nulo/tipo incompatível nunca resulta em `true`.
- **FR-9:** O executor deve percorrer os nodes `START`, `VALIDATION`, `DECISION`, `SUB_FLOW`, `END`, avaliar transições e persistir status e resultados.
- **FR-10:** Validators são `ValidatorStrategy` registrados por chave estável em um registry.
- **FR-11:** Timeout e retry configuráveis por node; cada tentativa é registrada.
- **FR-12:** `POST /executions` aceita `Idempotency-Key` com escopo por tenant.
- **FR-13:** Sub-flow com `inputMapping`/`outputMapping`, `maxDepth` e detecção de ciclo de composição.
- **FR-14:** O sistema deve manter histórico por node, transições avaliadas e eventos de auditoria.
- **FR-15:** Todo dado (fluxo, execução, auditoria) é associado a `tenantId` resolvido da credencial; nenhum acesso cruzado.
- **FR-16:** Duas superfícies de API logicamente separadas (Execution API e Flow Management API) com escopos distintos.
- **FR-17:** Estados de execução do MVP: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `TIMED_OUT`, `CANCELLED`, com transições controladas pelo domínio e locking otimista.
- **FR-18:** Grupos de documentos declarados no fluxo, com itens obrigatórios, alternativos e condicionais, registrados na execução.
- **FR-19:** O serviço expõe health/readiness e métricas de execução, latência, falha por validator, retry e timeout.
- **FR-20:** Logs sem segredos, tokens ou dados pessoais desnecessários.
- **FR-21:** Todas as APIs do MVP devem ser documentadas em um `openapi.yaml` versionado, validado no build e gerado a partir do código.
- **FR-22:** O contrato de execução deve ser compatível com evolução para entrega assíncrona do resultado via callback (sem quebrar clientes síncronos do MVP).
- **FR-23:** Multi-tenancy: toda query de repositório DEVE filtrar por `tenant_id`, obtido do `TenantPrincipal` autenticado — nunca do corpo da requisição. Toda story que adicionar acesso a dados deve incluir teste provando que o tenant A não lê nem modifica dados do tenant B.
- **FR-24:** Violações de unique constraint devem retornar 409 Conflict com `ErrorResponse`, nunca 500.

## 5. Non-Goals (fora do escopo do MVP)

- Frontend/editor visual de fluxos.
- Cache de respostas de provedores (RF-13 do documento-base: políticas, TTL, categorias de validade, fallback) — fase posterior.
- Nodes `ASYNC_VALIDATION`, `EXTERNAL_CALL`, `FORK`, `JOIN`, `HUMAN_APPROVAL`; callbacks, polling, dead-letter, retomada de execução pausada (`/resume`) e estados `WAITING_*`/`PAUSED`.
- Callback/webhook de resultado para o consumidor (previsto como evolução futura; o contrato do MVP não deve impedi-lo).
- Integração real com bureaus/provedores (no MVP apenas validators fake/simulados).
- Redis, broker de mensagens, particionamento de tabelas, réplicas de leitura, projeções analíticas.
- Drools, SpEL, JSONLogic, banco de grafos, Temporal.io.
- Modelos de machine learning e substituição de sistemas antifraude especializados.
- Implementação dos validators de negócio reais (CNPJ, Serasa, Bacen etc.).
- Taxonomia global de grupos/papéis; o engine não gerencia pertencimento de pessoas a grupos.
- Aprovação em dois níveis para ativação de fluxo.

## 6. Considerações de design

- **Sem UI no MVP.** Contratos via OpenAPI; futuro frontend consumirá a Flow Management API, nunca o banco.
- **DTOs REST ≠ domínio ≠ entidade JPA.** Fluxo de conversão: `Request -> Command -> Domain -> Entity`. `Map<String,Object>` apenas em trechos deliberadamente dinâmicos (`inputData`, `contextData`, `result`, `config`).
- **Formato do grafo:** `schemaVersion`, `startNodeId`, `nodes` em mapa por ID com `transitions` dentro de cada node.
- **Erros:** `ErrorResponse(code, message, retryable, details)` padronizado.

## 7. Considerações técnicas

- Regras de dependência: `domain` sem dependências; `application` depende só de `domain` (ports em `application/**/ports`); `adapter` implementa ports; `config` monta os beans.
- Cache em memória das definições de fluxo ativas, invalidado na ativação (otimização simples; sem Redis).
- Grafo é imutável após ativação; ativação em transação única com o índice único parcial como garantia final.
- Histórico no caminho síncrono limitado ao necessário para consistência da decisão; evitar snapshots excessivos (custo de armazenamento).
- Mapeamento de JSONPath para `inputMapping`/`outputMapping` com biblioteca já aceita pelo projeto (ex.: Jayway JsonPath).
- Testes: Testcontainers (PostgreSQL), unitários do avaliador e validação de grafo, concorrência para ativação e idempotência.
- Evolução para callback: o consumidor poderá informar uma URL de callback por tenant/requisição; o engine responderá `RUNNING` e fará `POST` do resultado (com assinatura e retry). Não implementar no MVP; manter `executionId`/`status` como base da resposta e `GET /executions/{id}` como fallback de consulta.
- `openapi.yaml` é *code-first*: gerado a partir dos controllers e DTOs (ex.: springdoc-openapi) e versionado no repositório; o código é a fonte da verdade.
- Credenciais: mecanismo exato (API key vs JWT) depende do padrão corporativo (pergunta em aberto).

## 8. Métricas de sucesso

- Os 18 critérios de aceite técnicos do MVP (seção 15 do documento-base) passam em testes automatizados.
- Adicionar um validator novo exige zero alteração no executor (provado por teste).
- Zero acesso cruzado entre tenants nos testes de isolamento.
- Teste de carga produz linha de base documentada (throughput, p50/p95/p99) antes de qualquer compromisso de SLO.
- 100% das execuções de teste reconstituíveis a partir do histórico (versão, conditions, tentativas por node).

## 9. Questões em aberto

1. Qual é o SLO de latência para fluxo leve e médio, e qual o pico a ser usado no teste de carga?
2. Decidido: MVP síncrono. Em aberto para o futuro: modelo do callback (URL por tenant ou por requisição, assinatura, política de retry, quais fluxos usam modo assíncrono).
3. Fluxos podem ter ciclos controlados ou são estritamente DAG? (MVP assume DAG.)
4. A versão do sub-flow é resolvida no início do pai ou no momento da chamada? (US-015 assume no momento da chamada.)
5. Qual retenção para execução, auditoria e dados pessoais?
6. Há padrão corporativo para autenticação, eventos e observabilidade?
7. Quais campos genéricos compõem o contrato de contexto padrão (papel, canal, grupos, região, produto)?
8. A ativação de fluxo exigirá aprovação de dois níveis em fase futura?
9. Qual é o primeiro caso de uso real que validará o MVP depois da entrega genérica?
