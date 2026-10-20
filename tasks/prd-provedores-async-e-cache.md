# PRD: Provedores assíncronos (callback e polling) + cache de respostas (parte 2 de 2)

> Depende de `prd-provedores-config-e-pausa.md`, **já concluído e mergeado**: usa `provider_config` (`ProviderConfigService`), o estado `WAITING` (`NodeStep.pause`), `ExecutionService.resume/cancel`, a expiração (`WaitExpiryService`) e a pausa em cascata de `SUB_FLOW`.
> Referências no v0.1: RF-07 (execução assíncrona), RF-13 (cache de respostas de provedores), seções 7.2 (módulo 6), 8.6 (`provider_cache_entry`) e 10.7.

## 1. Introdução

Provedores como Unico e BigDataCorp podem responder com um job e avisar depois (callback) ou exigir consulta periódica (polling). O `ProviderBackedValidator` atual bloqueia uma thread esperando a resposta, o que não serve para esses casos.

Além disso, cada execução paga uma chamada nova ao provedor, mesmo quando o dado tem validade conhecida (um score de crédito vale meses). O v0.1 pede cache persistente com política de validade explícita.

Este PRD entrega: (A) chamadas assíncronas com callback, polling e dead-letter, em cima da pausa do PRD anterior; (B) cache de respostas com TTL por política, auditável e isolado por tenant.

## 2. Objetivos

- Um node chamar um provedor assíncrono e a execução esperar sem ocupar thread.
- O resultado chegar por callback autenticado ou por polling, e a execução continuar.
- Falhas e expirações irem para dead-letter visível e reprocessável.
- Reaproveitar respostas válidas dentro de uma política explícita, sem nunca usar dado vencido em silêncio.
- Cada decisão de cache (hit, miss, expirado, bypass) ficar registrada na execução.

## 3. Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes
- [ ] Migration Flyway nova por story que muda schema; nunca editar as existentes
- [ ] `openapi.yaml` atualizado quando a API mudar
- [ ] Regras do ArchUnit passam
- [ ] Tudo escopado por `tenantId`, com teste de isolamento
- [ ] Nenhum segredo, token, documento completo ou corpo bruto de resposta em log, em `node_execution` ou em erro
- [ ] Testes usam um provedor falso; nenhuma chamada a API real

## 4. User Stories

### Parte A: provedores assíncronos

### US-001: Contrato assíncrono do DataProvider
**Descrição:** Como desenvolvedor de adapter, quero declarar que um provedor responde de forma assíncrona.

**Critérios:**
- [ ] `DataProvider` ganha `start(config, params)` que devolve `ProviderResult`: `Done(data)` ou `Pending(externalRef, mode, pollAfter)` com `mode` = `CALLBACK` ou `POLL` (o `ProviderConfig` do tenant é passado, como já ocorre em `fetch(config, params)`)
- [ ] Provedor assíncrono implementa também `poll(config, externalRef)` que devolve `ProviderResult`
- [ ] O comportamento síncrono atual (`fetch`) continua válido: `start` tem implementação padrão que chama `fetch` e devolve `Done`
- [ ] `ProviderException` continua sendo o único canal de falha, com `retryable`
- [ ] Teste de contrato para um provedor falso nos três caminhos

### US-002: Tabela de chamadas pendentes
**Descrição:** Como motor, quero registrar cada chamada que ficou aguardando resposta.

**Critérios:**
- [ ] Migration cria `provider_call`: `id`, `tenant_id`, `execution_id`, `node_id`, `provider_key`, `external_ref`, `mode`, `status` (`PENDING|COMPLETED|FAILED|DEAD`), `attempts`, `next_poll_at`, `deadline`, `callback_token_hash`, `created_at`, `updated_at`
- [ ] Índice parcial em `next_poll_at` para `status = 'PENDING' AND mode = 'POLL'`
- [ ] Porta de repositório e adapter JPA no padrão existente
- [ ] O token do callback é guardado só como hash; o valor em claro existe apenas no momento da criação

### US-003: Node EXTERNAL_CALL
**Descrição:** Como analista, quero um node que chame um provedor e aguarde a resposta sem bloquear.

**Critérios:**
- [ ] Novo node type `EXTERNAL_CALL`, config: `providerKey`, `query` (parâmetro do provedor → campo de `inputData`), `expect` (opcional), `maxWait` (duração, limitada por `app.limits.max-wait`)
- [ ] Reaproveita a resolução de config do PRD anterior (`PROVIDER_DISABLED` quando desligado) e a mesma regra de `query`/`expect` do `ProviderBackedValidator`
- [ ] `Done` segue o fluxo normal, com a mesma semântica de transições de saída de um node `VALIDATION` (resultado ok ou não ok); `Pending` cria a `provider_call` e pausa com `NodeStep.pause(...)` em `WAITING` (motivo `PROVIDER_PENDING`) com prazo igual a `maxWait`
- [ ] Dentro de um `SUB_FLOW`, a pausa propaga ao pai pelo mecanismo já existente (nenhuma lógica nova de cascata)
- [ ] A validação estrutural do grafo exige `maxWait` e as transições de sucesso e de falha
- [ ] Aparece em `GET /api/v1/node-types`; a ativação rejeita provedor desligado (mesma regra do PRD anterior)

### US-004: Endpoint de callback
**Descrição:** Como provedor externo, quero entregar o resultado de uma chamada pendente.

**Critérios:**
- [ ] `POST /api/v1/provider-callbacks/{callId}` fora do esquema de API key; autenticado pelo token único da chamada (header) comparado com `callback_token_hash` em tempo constante
- [ ] Opcionalmente valida assinatura HMAC do corpo quando `provider_config` tiver `callback_secret_ref`
- [ ] O adapter do provedor normaliza o corpo recebido para o mesmo formato canônico do `fetch`
- [ ] Chamada só aceita em `PENDING`; repetição do mesmo callback é idempotente (200 sem efeito), chamada já `DEAD` ou `FAILED` retorna 409
- [ ] Callback válido grava o resultado no histórico do node e retoma a execução a partir do node em espera, reaproveitando a continuação de `ExecutionService.resume` (que aceita um node inicial); a transição seguida é a do resultado do provedor, não a de `APPROVED|REJECTED`
- [ ] Corpo limitado por `app.limits.max-body-bytes`; token inválido retorna 401 sem revelar se a chamada existe
- [ ] Teste: callback correto, token errado, duplicado e fora de ordem com expiração

### US-005: Polling com backoff
**Descrição:** Como motor, quero consultar periodicamente os provedores que não enviam callback.

**Critérios:**
- [ ] Job (`app.providers.poll-interval`) seleciona `provider_call` `PENDING` em modo `POLL` com `next_poll_at` vencido, com `FOR UPDATE SKIP LOCKED` para não duplicar entre instâncias
- [ ] `poll` devolvendo `Pending` reagenda com backoff exponencial limitado (`app.providers.poll-max-delay`); `Done` conclui e retoma a execução
- [ ] `ProviderException` com `retryable=true` incrementa `attempts` e reagenda; `retryable=false` ou `attempts` acima de `app.providers.max-attempts` leva a `FAILED`
- [ ] A chamada respeita a config do tenant (desligada vale como falha não reprocessável)
- [ ] Teste com relógio controlado e provedor falso que demora N consultas

### US-006: Dead-letter e reprocessamento
**Descrição:** Como operador, quero ver e reprocessar chamadas que não terminaram.

**Critérios:**
- [ ] `FAILED` por esgotar tentativas e chamadas cuja execução expirou (`WAIT_EXPIRED`) passam a `DEAD`, com motivo
- [ ] `GET /api/v1/provider-calls?status=DEAD` lista por tenant, com paginação; escopo `provider:admin`
- [ ] `POST /api/v1/provider-calls/{id}/retry` recoloca em `PENDING` zerando `attempts`, somente se a execução ainda estiver em `WAITING`; caso contrário 409
- [ ] Ao expirar a execução, a `provider_call` correspondente deixa de ser consultada
- [ ] Teste de isolamento entre tenants

### US-007: Observabilidade da parte assíncrona
**Descrição:** Como operador, quero métricas do que está pendente.

**Critérios:**
- [ ] Gauges `validation.provider.pending` e `validation.provider.dead`, contadores `validation.provider.callback|poll` com tags `provider` e `outcome`
- [ ] Logs de callback e polling com `executionId`, `callId`, `providerKey` e status, sem token nem corpo
- [ ] `tasks/coverage.md`: RF-07 sai de "Adiado" e `README.md` descreve o fluxo assíncrono

### Parte B: cache de respostas

### US-008: Tabela de cache e política
**Descrição:** Como motor, quero guardar respostas normalizadas com validade explícita (v0.1, seção 8.6).

**Critérios:**
- [ ] Migration cria `provider_cache_entry`: `id`, `tenant_id`, `provider_key`, `operation`, `request_key`, `response` (JSONB), `data_category`, `fetched_at`, `valid_until`, `policy_applied`
- [ ] Índices únicos por `(tenant_id, provider_key, operation, request_key)` e por `valid_until` para limpeza
- [ ] Migration cria `provider_cache_policy`: `tenant_id`, `provider_key`, `operation`, `data_category`, `ttl_seconds`, `fallback_on_error_allowed`, `justification`, `owner`, `review_date`
- [ ] `request_key` é o SHA-256 da forma canônica dos parâmetros (mesma canonicalização da idempotência); o documento em claro **não** é gravado
- [ ] Sem política cadastrada para `(tenant, provider, operation)`, nada é cacheado
- [ ] Teste de isolamento: nem tenant nem provedor reaproveitam a entrada do outro

### US-009: Resolução de TTL
**Descrição:** Como motor, quero que o TTL venha de política e nunca do código.

**Critérios:**
- [ ] `CachePolicyService.resolve(tenant, provider, operation)` devolve a política aplicável ou vazio
- [ ] Categorias padrão (`LOW_VOLATILITY`, `MEDIUM_VOLATILITY`, `HIGH_VOLATILITY`, `REAL_TIME`) com TTL padrão configurável; `REAL_TIME` nunca cacheia
- [ ] A categoria `CUSTOM` só é aceita com `justification`, `owner`, `review_date` e `ttl_seconds` preenchidos; incompleta é rejeitada na gravação (400) e tratada como "sem política" na leitura
- [ ] TTL efetivo é o menor entre o da política e `app.providers.cache-max-ttl`
- [ ] Teste por categoria e por política inválida

### US-010: Cache no caminho da chamada
**Descrição:** Como motor, quero consultar o cache antes de chamar o provedor.

**Critérios:**
- [ ] Antes de `fetch`/`start`, consulta o cache; `HIT` válido devolve a resposta sem chamar o provedor; `MISS` e `EXPIRED` chamam o provedor e gravam o resultado
- [ ] Node aceita `cache: "BYPASS"` na config para forçar consulta nova (e atualizar a entrada)
- [ ] Só respostas `Done` bem-sucedidas são gravadas; erro do provedor nunca vira entrada
- [ ] O resultado do node registra `cacheStatus` (`HIT|MISS|EXPIRED|BYPASS|DISABLED`), `cacheAgeSeconds` e a política aplicada
- [ ] Resposta `found:false` (ex.: 404 da BrasilAPI) segue a política, mas com TTL no máximo igual a `app.providers.negative-cache-ttl`
- [ ] Teste: segunda execução idêntica não chama o provedor; mudar um parâmetro gera `MISS`

### US-011: Fallback controlado para dado vencido
**Descrição:** Como analista, quero poder aceitar um dado vencido quando o provedor estiver fora, com isso registrado.

**Critérios:**
- [ ] Só ocorre se a política tiver `fallback_on_error_allowed = true` e o erro for `retryable` esgotado
- [ ] O resultado do node marca `cacheStatus: STALE_FALLBACK`, `cacheAgeSeconds` e o motivo da falha; nunca é silencioso
- [ ] Sem permissão na política, o erro do provedor segue como falha normal
- [ ] Teste dos dois caminhos

### US-012: Limpeza, privacidade e métricas do cache
**Descrição:** Como operador, quero que o cache não acumule dado vencido nem exponha dado pessoal.

**Critérios:**
- [ ] Job de limpeza (`app.retention.provider-cache`, desligado se não configurado, como a retenção de idempotência) remove entradas com `valid_until` vencido há mais que a carência configurada
- [ ] A resposta gravada contém apenas os campos normalizados do adapter (sem corpo bruto do provedor)
- [ ] Contadores `validation.provider.cache` com tags `provider` e `result` (`hit|miss|expired|bypass|stale_fallback`)
- [ ] `tasks/coverage.md`: RF-13 sai de "Adiado" e `README.md` explica política, TTL e `BYPASS`

## 5. Requisitos funcionais

- FR-1: Uma chamada `Pending` pausa a execução e não ocupa thread.
- FR-2: O callback só é aceito com o token da própria chamada; o token é guardado como hash.
- FR-3: Polling não executa duas vezes a mesma chamada em instâncias diferentes.
- FR-4: Chamada que esgota tentativas ou expira vai para dead-letter e pode ser reprocessada enquanto a execução esperar.
- FR-5: Sem política de cache para o par provedor/operação, não há cache.
- FR-6: Dado vencido só é usado com permissão explícita da política e com registro na execução.
- FR-7: O cache nunca é compartilhado entre tenants nem entre provedores.

## 6. Não-objetivos

- Adapters reais de BigDataCorp, Unico ou outro provedor pago (cada um é um PRD próprio, usando estes contratos).
- Redis ou Data Grid como camada de aceleração (PostgreSQL é a fonte de verdade; camada quente fica para depois, com medição).
- Circuit breaker e rate limit por provedor.
- Broker de mensagens para callbacks.
- Particionamento da tabela de cache (reavaliar com volume real).
- `fork/join`.
- Interface de gestão de políticas.

## 7. Considerações técnicas

- Criptografia em repouso e política de retenção da coluna `response` seguem o padrão corporativo; confirmar se basta a criptografia de disco do PostgreSQL.
- Reaproveitar a canonicalização de `IdempotentExecutionService` para o `request_key` (extrair para um utilitário compartilhado em vez de duplicar).
- O callback é a única rota pública a autenticar por token de chamada; isolá-la no `SecurityConfig` e cobrir com teste de segurança.
- Polling e expiração disputam a mesma execução com `resume`/`cancel`: usar o lock otimista já existente.
- Medir com o `loadtest/` o custo de gravar e ler o cache.

## 8. Métricas de sucesso

- Fluxo com `EXTERNAL_CALL` fecha pelos dois caminhos (callback e polling) em testes de integração.
- Chamada que nunca responde termina em dead-letter e a execução em `TIMED_OUT`.
- Segunda execução idêntica dentro do TTL não gera chamada ao provedor (verificado por contador do provedor falso).
- Nenhum teste encontra segredo, token ou documento em claro em log, em `node_execution` ou em `provider_cache_entry`.

## 9. Perguntas em aberto

- TTLs iniciais por categoria (o v0.1 sugere 3 meses para score e 6 meses para RG, sujeitos a validação jurídica): quem aprova os valores padrão?
- Cache de resposta `found:false` deve existir? Proposto: sim, com TTL curto.
- A assinatura HMAC do callback é obrigatória para qualquer provedor real ou só quando o provedor oferecer?
- O mesmo `request_key` com `operation` diferente (ex.: consulta cadastral e consulta de sanções) precisa de TTLs distintos: confirmar que `operation` basta como chave.
