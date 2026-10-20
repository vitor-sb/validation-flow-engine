# PRD: Configuração de provedores no banco + pausa, retomada e cancelamento (parte 1 de 2)

> Primeiro de dois PRDs. O segundo (`06-prd-provedores-async-e-cache.md`) depende deste: ele usa o estado `WAITING` e o `resume` criados aqui.
> Base: branch `claude/architecture-external-providers-tyqpdx` (porta `DataProvider`, `ProviderBackedValidator`, `BrasilApiCnpjProvider`). Mergear antes de começar.

## 1. Introdução

Hoje um provedor externo é ligado por variável de ambiente (`APP_PROVIDERS_BRASILAPI_ENABLED`): vale para todos os tenants, exige restart e não deixa rastro de quem mudou. As credenciais por tenant que o v0.1 exige (seção 10 e RF-13) também não têm onde morar.

Além disso, o motor só executa de ponta a ponta numa requisição. Não existe pausa, então não dá para ter `HUMAN_APPROVAL` (RF-08), `resume`/`cancel` (RF-12) nem esperar um provedor assíncrono.

Este PRD faz duas coisas: (A) move a configuração de provedores para uma tabela por tenant; (B) cria o estado de espera, o node `HUMAN_APPROVAL` e os endpoints de retomada e cancelamento.

## 2. Objetivos

- Ligar, desligar e configurar um provedor por tenant sem restart nem deploy.
- Um provedor sem configuração fica **desligado** (nenhuma chamada de saída por padrão).
- Nenhum segredo em texto puro no banco, nos logs ou nas respostas da API.
- Uma execução poder pausar, ser retomada com dados validados, ser cancelada ou expirar.
- Nenhuma mudança no comportamento das execuções síncronas atuais.

## 3. Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes
- [ ] Migration Flyway nova por story que muda schema (`V3__...`, `V4__...`), nunca editar as existentes
- [ ] `openapi.yaml` atualizado quando a API mudar
- [ ] Regras do ArchUnit passam: JPA e Spring Data só em `adapter.out.postgres`
- [ ] Tudo escopado por `tenantId`; teste de isolamento entre dois tenants nas stories que leem ou gravam dados
- [ ] Comentários e mensagens seguem o estilo do código vizinho

## 4. User Stories

### Parte A: provedores configurados no banco

### US-001: Tabela e porta de configuração de provedores
**Descrição:** Como mantenedor, quero a configuração de cada provedor por tenant no banco para não depender de env nem de restart.

**Critérios:**
- [ ] Migration cria `provider_config`: `tenant_id`, `provider_key`, `enabled` (default false), `base_url`, `connect_timeout_ms`, `read_timeout_ms`, `credentials_ref`, `updated_by`, `updated_at`; chave primária `(tenant_id, provider_key)`
- [ ] Record de domínio `ProviderConfig` e porta `ProviderConfigRepository` em `application`
- [ ] Adapter JPA em `adapter.out.postgres` (entity, repository, mapper), seguindo o padrão das outras entidades
- [ ] Teste de integração: gravar, ler, atualizar; um tenant não lê a linha do outro

### US-002: Resolução de configuração com cache curto
**Descrição:** Como motor, quero resolver a configuração do provedor a cada chamada sem consultar o banco toda vez.

**Critérios:**
- [ ] `ProviderConfigService.resolve(tenantId, providerKey)` devolve `Optional<ProviderConfig>`; sem linha, ou `enabled=false`, conta como desligado
- [ ] Cache em memória com TTL configurável (`app.providers.config-cache-ttl`, padrão `PT30S`)
- [ ] A atualização pela API (US-005) invalida a entrada no mesmo processo
- [ ] Teste: mudar `enabled` no banco passa a valer depois do TTL, sem restart

### US-003: Credenciais por referência
**Descrição:** Como operador, quero guardar só uma referência ao segredo, nunca o segredo.

**Critérios:**
- [ ] Porta `CredentialResolver.resolve(String ref)` em `application`
- [ ] Implementação que lê a variável de ambiente cujo nome é o `credentials_ref`
- [ ] Referência que não resolve gera `ProviderException` `PROVIDER_MISCONFIGURED`, `retryable=false`, sem incluir o valor nem o nome do segredo na mensagem
- [ ] Teste garante que o segredo não aparece em log, em `node_execution` nem em resposta da API

### US-004: Migrar a BrasilAPI da env para a tabela
**Descrição:** Como mantenedor, quero que `brasilapi-cnpj` seja controlado pela tabela e não por `APP_PROVIDERS_BRASILAPI_ENABLED`.

**Critérios:**
- [ ] Remover `@ConditionalOnProperty` e a variável do `.env.example`; o validador `brasilapi-cnpj` fica sempre registrado
- [ ] `ProviderBackedValidator` resolve a config por `(tenantId, providerKey)` a cada execução; sem config ou desligado falha com `PROVIDER_DISABLED`, `retryable=false`
- [ ] `base_url` e timeouts vêm da linha; `RestClient` por configuração fica em cache e é recriado quando a linha muda
- [ ] `BrasilApiCnpjProvider` continua sem credencial; padrões de `base_url` e timeouts usados quando a coluna é nula
- [ ] Testes existentes da branch continuam passando, adaptados para inserir a linha de configuração
- [ ] Teste: tenant A habilitado e tenant B desabilitado no mesmo processo

### US-005: API de gestão de provedores
**Descrição:** Como administrador do tenant, quero consultar e alterar a configuração dos provedores pela API.

**Critérios:**
- [ ] `GET /api/v1/providers` lista as linhas do tenant; `PUT /api/v1/providers/{providerKey}` cria ou atualiza
- [ ] Escopos novos: `provider:read` e `provider:admin`; `SecurityConfig` e `.env.example` atualizados
- [ ] `providerKey` desconhecido (sem adapter registrado) retorna 404; `base_url` precisa ser `https` fora do perfil `local`
- [ ] A resposta nunca devolve o valor do segredo, só a referência
- [ ] Cada alteração grava `updated_by` (identidade da API key) e um registro de auditoria
- [ ] `openapi.yaml` atualizado

### US-006: Rejeitar fluxo que usa provedor desligado
**Descrição:** Como analista, quero ser avisado na ativação se o fluxo depende de um provedor desligado.

**Critérios:**
- [ ] `ValidatorStrategy` de provedor expõe a `providerKey` (a capability `EXTERNAL_PROVIDER` já existe)
- [ ] `PATCH /flows/{id}/activate` retorna 409 listando os nodes cujo provedor está desligado para o tenant
- [ ] `POST /flows/validate` devolve o mesmo problema como aviso, sem bloquear
- [ ] Teste: ligar o provedor e ativar passa

### Parte B: pausa, retomada e cancelamento

### US-007: Estado WAITING e persistência da espera
**Descrição:** Como motor, quero um estado em que a execução fica parada e fora de qualquer thread.

**Critérios:**
- [ ] `ExecutionStatus` ganha `WAITING`
- [ ] Migration adiciona a `flow_execution`: `waiting_node_id`, `wait_deadline` (timestamptz), `wait_reason` (texto curto) e índice parcial em `wait_deadline` onde `status = 'WAITING'`
- [ ] Transições permitidas em `FlowExecution`: `RUNNING → WAITING`, `WAITING → RUNNING | CANCELLED | TIMED_OUT`; as demais continuam proibidas e testadas
- [ ] `RecoveryService` ignora `WAITING` (não é "abandonada")
- [ ] Teste de domínio cobre as transições válidas e inválidas

### US-008: Node handler que pausa
**Descrição:** Como desenvolvedor, quero que um `NodeHandler` possa pedir a pausa da execução.

**Critérios:**
- [ ] `NodeHandler.handle` consegue sinalizar "pausar aqui" com motivo e prazo, mantendo o contrato atual para os handlers existentes
- [ ] O laço `walk` persiste `WAITING` (nó, prazo, motivo), grava o histórico do node como pendente e devolve a execução
- [ ] `POST /executions` responde `202 Accepted` quando o resultado é `WAITING` (e `201` nos demais casos); `openapi.yaml` documenta os dois
- [ ] `IdempotentExecutionService` trata `WAITING` como execução criada: o replay devolve a mesma execução em `WAITING`
- [ ] Teste com handler falso que pausa, sem depender de `HUMAN_APPROVAL`

### US-009: Node HUMAN_APPROVAL
**Descrição:** Como analista, quero um node que pare o fluxo até uma pessoa decidir (RF-08).

**Critérios:**
- [ ] Novo node type `HUMAN_APPROVAL`, config: `reason`, `assignedGroup` (opcional), `deadline` (duração ISO-8601, limitada por `app.limits.max-wait`)
- [ ] Validação estrutural do grafo exige `deadline` e ao menos as transições `APPROVED` e `REJECTED`
- [ ] Ao executar, pausa com `WAITING`, guardando motivo, grupo e prazo
- [ ] Aparece em `GET /api/v1/node-types`
- [ ] Teste de ponta a ponta até o `WAITING`

### US-010: Retomar execução
**Descrição:** Como sistema ou pessoa autorizada, quero retomar uma execução pausada com uma decisão (RF-12).

**Critérios:**
- [ ] `POST /api/v1/executions/{id}/resume` com `{ "decision": "APPROVED|REJECTED", "note": "...", "evidence": {...} }`
- [ ] Escopo novo `validation:resume`; só retoma execução do próprio tenant
- [ ] Retoma do node em espera usando o **snapshot** original e segue pela transição correspondente à decisão
- [ ] Decisão, observação, evidências e quem decidiu ficam no histórico do node e na auditoria
- [ ] Execução que não está em `WAITING` retorna 409; payload inválido retorna 400; retomada repetida é idempotente (a segunda recebe 409 sem reexecutar nada)
- [ ] Duas retomadas concorrentes: só uma vence (lock otimista), a outra recebe 409
- [ ] Teste: aprovar leva a `COMPLETED`; rejeitar segue o ramo `REJECTED`

### US-011: Cancelar execução
**Descrição:** Como sistema consumidor, quero cancelar uma execução que está esperando (RF-12).

**Critérios:**
- [ ] `POST /api/v1/executions/{id}/cancel` com motivo opcional; escopo `validation:cancel`
- [ ] Só `WAITING` pode ser cancelada; `RUNNING` retorna 409 com mensagem clara (cancelamento cooperativo fica fora do escopo); estados finais retornam 409
- [ ] Vai para `CANCELLED` com `completed_at`, motivo e auditoria
- [ ] Teste de isolamento entre tenants

### US-012: Expiração de execuções em espera
**Descrição:** Como operador, quero que uma execução parada além do prazo termine sozinha.

**Critérios:**
- [ ] Job periódico (`app.wait.expiry-interval`) move `WAITING` com `wait_deadline` vencido para `TIMED_OUT`, com `errorInfo` `WAIT_EXPIRED` e auditoria
- [ ] Usa o lock otimista: se uma retomada ganhou a corrida, a expiração é ignorada
- [ ] Não processa linhas de outro nó nem estados que não sejam `WAITING`
- [ ] Teste com relógio controlado

### US-013: Observabilidade e documentação
**Descrição:** Como operador, quero ver quantas execuções estão esperando e atualizar a documentação.

**Critérios:**
- [ ] Gauge `validation.execution.waiting` e contadores `validation.execution.resumed|cancelled|wait_expired` na porta `ExecutionMetrics`
- [ ] Logs continuam sem segredo nem dado pessoal
- [ ] `tasks/coverage.md`: RF-08 e RF-12 saem de "Adiado"; `README.md` atualizado (nodes, escopos, provedores no banco)

### Parte C: pausa dentro de SUB_FLOW

> Acrescentada depois do ciclo inicial: o filho pausado era reportado como `SUB_FLOW_FAILED`. O pai e o filho devem ficar os dois em espera.

### US-014: Filho pausado deixa o pai em espera
**Descrição:** Como analista, quero que um sub-flow com `HUMAN_APPROVAL` pause também o fluxo pai, sem falhar.

**Critérios:**
- [ ] Quando o filho termina `WAITING`, o pai vai para `WAITING` no node `SUB_FLOW` (`waiting_node_id` = esse node, motivo `SUB_FLOW_WAITING`), sem `SUB_FLOW_FAILED`
- [ ] O prazo do pai é o menor entre o do filho e o do próprio pai; a ligação pai/filho usa `parent_execution_id` e `parent_node_id` já existentes
- [ ] O histórico do node `SUB_FLOW` fica como pendente, com o id da execução filha
- [ ] Funciona em vários níveis (neto pausado deixa filho e pai em espera), respeitando o limite de profundidade existente
- [ ] `POST /executions` do pai responde `202`
- [ ] Teste: pai com `SUB_FLOW` cujo filho tem `HUMAN_APPROVAL` termina em `WAITING`, pai e filho

### US-015: Retomar o filho continua o pai
**Descrição:** Como aprovador, quero decidir no filho e ver o fluxo pai seguir sozinho.

**Critérios:**
- [ ] `POST /executions/{id}/resume` aceita o id do **filho**; ao terminar o filho, o pai retoma do node `SUB_FLOW` com o mapeamento de saída e segue as transições normais
- [ ] Filho que ainda termina em `WAITING` (novo `HUMAN_APPROVAL` no mesmo filho) mantém o pai em espera
- [ ] Filho que termina `FAILED` ou `TIMED_OUT` segue o tratamento atual de falha de sub-flow no pai
- [ ] `resume` com o id do **pai** em espera por filho retorna 409 informando o id do filho a retomar
- [ ] A retomada em cadeia usa o lock otimista nos dois níveis; duas retomadas concorrentes: uma vence
- [ ] Teste de ponta a ponta: aprovar o filho leva pai e filho a `COMPLETED`; rejeitar segue o ramo `REJECTED` do filho

### US-016: Cancelar e expirar em cascata
**Descrição:** Como operador, quero que cancelar ou expirar um lado não deixe o outro órfão.

**Critérios:**
- [ ] Cancelar o pai em espera cancela os filhos em espera; cancelar o filho cancela o pai (motivo `CHILD_CANCELLED`)
- [ ] A expiração do pai ou do filho expira o outro (`WAIT_EXPIRED`), com auditoria nos dois
- [ ] Execução já finalizada não é alterada; o lock otimista decide corridas
- [ ] `RecoveryService` e o job de expiração não geram pai `WAITING` sem filho `WAITING` (teste de consistência)
- [ ] `tasks/coverage.md` e `README.md` descrevem o comportamento; métricas `resumed|cancelled|wait_expired` contam cada execução afetada

## 5. Requisitos funcionais

- FR-1: Provedor sem linha em `provider_config` para o tenant é tratado como desligado.
- FR-2: Alterar a configuração vale em até `app.providers.config-cache-ttl`, sem restart.
- FR-3: Segredos são resolvidos por referência em tempo de uso e nunca persistidos nem devolvidos.
- FR-4: Um fluxo só ativa se os provedores que usa estiverem ligados para o tenant.
- FR-5: Uma execução pode estar em `WAITING` por no máximo `app.limits.max-wait`.
- FR-6: Retomar exige escopo próprio e usa o snapshot da execução, não a definição atual do fluxo.
- FR-7: Retomar, cancelar e expirar competem pelo mesmo lock otimista; exatamente um resultado vence.
- FR-8: Um filho de `SUB_FLOW` em `WAITING` mantém o pai em `WAITING`; retomar, cancelar ou expirar um lado propaga ao outro.

## 6. Não-objetivos

- Provedor assíncrono, callback, polling, dead-letter e cache de respostas (próximo PRD).
- Interface de gestão ou de aprovação (só API).
- Cofre de segredos externo (Vault, AWS Secrets Manager); a referência por variável de ambiente é o primeiro passo e a porta `CredentialResolver` permite trocar depois.
- Cancelar uma execução `RUNNING` no meio do processamento.
- Notificar o aprovador (e-mail, fila).
- Aprovação em dois níveis para ativar fluxo.
- Adapters de provedores pagos (BigDataCorp, Unico).

## 7. Considerações técnicas

- Reaproveitar o padrão entity/repository/mapper de `adapter.out.postgres` e o `UPDATE ... WHERE lock_version = ?` de `flow_execution`.
- `walk` hoje devolve uma execução terminal ou continua; a pausa é um terceiro resultado. Alterar o mínimo nos handlers existentes.
- O `ValidatorRunner` já aplica timeout e retry; `PROVIDER_DISABLED` e `PROVIDER_MISCONFIGURED` são `retryable=false`.
- `IdempotentExecutionService` hoje assume que `execute` devolve uma execução concluída; conferir o replay de uma `WAITING`.

## 8. Métricas de sucesso

- Ligar e desligar um provedor para um tenant sem reiniciar o processo, com teste automatizado.
- Fluxo com `HUMAN_APPROVAL` percorre `WAITING → resume → COMPLETED` e `WAITING → cancel` e `WAITING → expiração` em testes de integração.
- Baseline do `loadtest/` sem queda maior que 10% (as execuções síncronas não pagam pela pausa).

## 9. Perguntas em aberto

- `202` para `WAITING` quebra algum consumidor que assume `201`? Confirmar antes de mudar o contrato.
- `deadline` do `HUMAN_APPROVAL` deve ter padrão por tenant ou ser sempre explícito (proposto: sempre explícito)?
- Quem pode retomar: qualquer chave com `validation:resume` ou restrita ao `assignedGroup`? (proposto: escopo apenas, grupo é informativo)
