# PRD: Cache por operação e chave de consulta (RF-13, complemento)

> Depende de `06-prd-provedores-async-e-cache.md`, **já concluído e mergeado**: usa `provider_cache_policy`, `ProviderCache`, `CachePolicyService` e a API `/providers/{providerKey}/cache-policies/{operation}`.
> Referência no v0.1: RF-13 (`operationKey`, `keyFields`), seção 8.6.

## 1. Introdução

Hoje a política de cache é, na prática, uma por provedor. Os nós não têm o conceito de "operação", então toda chamada cacheada usa a operação fixa `default` (`ProviderCache.OPERATION`). O mesmo provedor com duas consultas de validade diferente (cadastral e sanções, por exemplo) não consegue ter dois TTLs.

Além disso, a chave da entrada é o hash de **todos** os parâmetros enviados ao provedor, exatamente como estão. Duas consequências:

- Um CNPJ escrito `12.345.678/0001-95` e outro como `12345678000195` geram chaves diferentes, e a segunda consulta paga de novo (o adapter da BrasilAPI normaliza o CNPJ só dentro do `fetch`).
- Se a consulta levar um parâmetro que não muda a resposta (um identificador de requisição, um campo de contexto), cada chamada vira um MISS.

Este PRD entrega: (1) a operação declarada no nó; (2) `keyFields` na política, para escolher quais parâmetros identificam a consulta; (3) normalização da chave pelo adapter.

## 2. Objetivos

- Um nó declarar a operação que executa, e a política de cache ser resolvida por `(tenant, provedor, operação)` de verdade.
- A política dizer quais parâmetros compõem a chave de cache.
- Valores equivalentes (CNPJ formatado e só dígitos) caírem na mesma entrada.
- Manter compatível o que já existe: nós sem `operation` continuam usando `default`, e as políticas já cadastradas em `default` continuam valendo.

## 3. Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes
- [ ] Migration Flyway nova por story que muda schema; nunca editar as existentes
- [ ] `openapi.yaml` atualizado quando a API mudar
- [ ] Regras do ArchUnit passam
- [ ] Tudo escopado por `tenantId`, com teste de isolamento
- [ ] Nenhum documento em claro em log, em `node_execution` ou em `provider_cache_entry` (a chave continua sendo hash)
- [ ] Testes usam um provedor falso; nenhuma chamada a API real
- [ ] Teste de carga rodado fora de `/workspace` (ver `loadtest/REPORT.md`), sem queda acima de 10% frente ao baseline

## 4. User Stories

### US-001: Operação declarada no nó
**Descrição:** Como analista de fluxo, quero dizer qual operação do provedor o nó executa, para que ela tenha política própria.

**Critérios:**
- [ ] Nós `EXTERNAL_CALL` e `VALIDATION` baseados em provedor aceitam `operation` na config (no mesmo nível de `cache`)
- [ ] Ausente, vale `default` (comportamento atual preservado)
- [ ] O validador de grafo rejeita `operation` fora de `[A-Za-z0-9_.-]{1,100}`
- [ ] O validador de grafo passa a rejeitar `cache` com valor diferente de `"BYPASS"` (hoje qualquer valor é aceito e ignorado)
- [ ] `ProviderCache.OPERATION` deixa de ser usada diretamente nos handlers: lookup, `store` e `staleFallback` recebem a operação do nó
- [ ] A conclusão assíncrona (callback e polling, `ProviderCallbackService.complete`) grava na operação do nó que originou a chamada
- [ ] O resultado do nó registra `cacheOperation` junto de `cacheStatus` e `cachePolicy`
- [ ] Teste: dois nós do mesmo provedor com operações diferentes usam políticas e TTLs diferentes; nó sem `operation` continua achando a política `default`

### US-002: `keyFields` na política
**Descrição:** Como administrador, quero escolher quais parâmetros da consulta identificam a entrada de cache.

**Critérios:**
- [ ] Migration adiciona `key_fields` (lista de nomes de parâmetro) a `provider_cache_policy`; nulo significa "todos os parâmetros" (comportamento atual)
- [ ] `PUT /providers/{providerKey}/cache-policies/{operation}` aceita e devolve `keyFields`; lista vazia ou com nome duplicado ou em branco é 400; a gravação é auditada como hoje
- [ ] `request_key` é o SHA-256 canônico só dos parâmetros listados
- [ ] Se algum `keyField` não existir nos parâmetros da consulta, **não há cache** naquela chamada (status `DISABLED`, aviso no log com o nome do campo); nunca se monta uma chave parcial
- [ ] Alterar `keyFields` deixa as entradas antigas inalcançáveis (chave nova); elas expiram pelo TTL ou pela limpeza. Documentar no README
- [ ] Teste: parâmetro fora de `keyFields` não gera MISS; `keyField` ausente desliga o cache; chave nunca mistura tenants

### US-003: Normalização da chave pelo adapter
**Descrição:** Como desenvolvedor de adapter, quero que valores equivalentes gerem a mesma chave.

**Critérios:**
- [ ] `DataProvider` ganha `Map<String, Object> cacheKeyParams(Map<String, Object> params)` com implementação padrão que devolve os parâmetros como estão
- [ ] A normalização roda antes de aplicar `keyFields` e antes do hash
- [ ] O adapter da BrasilAPI normaliza `cnpj` (só dígitos, reaproveitando `BrazilianDocuments.normalize`)
- [ ] Teste: CNPJ formatado e CNPJ só com dígitos compartilham a mesma entrada (segunda consulta é HIT); um CNPJ diferente continua sendo MISS
- [ ] Nada do valor normalizado é gravado em claro

### US-004: Documentação e rastreabilidade
**Critérios:**
- [ ] `README.md`: como declarar `operation`, como definir `keyFields`, e o efeito de alterá-los
- [ ] `tasks/coverage.md`: RF-13 atualizado (operação e `keyFields` saem da lista de lacunas)
- [ ] Teste de carga repetido, fora de `/workspace`, com o resultado registrado em `loadtest/REPORT.md`

## 5. Requisitos funcionais

- FR-1: A política de cache é resolvida por `(tenant, provedor, operação)`, e a operação vem do nó.
- FR-2: Sem `operation` no nó, a operação é `default`.
- FR-3: A chave da entrada é o hash dos parâmetros normalizados pelo adapter e filtrados por `keyFields`.
- FR-4: Chave incompleta nunca é montada: sem todos os `keyFields`, não há cache.
- FR-5: Callback, polling e fallback para dado vencido usam a mesma operação e a mesma chave do nó.

## 6. Não-objetivos

- Política **versionada** de cache e histórico de versões.
- `sensitivity`, criptografia por política e retenção por sensibilidade.
- Invalidação por evento de negócio.
- TTL por produto, canal, tipo de usuário ou evento.
- Aprovação formal de categorias `CUSTOM`.
- Redis ou Data Grid como camada quente.
- Interface de gestão de políticas.

## 7. Considerações técnicas

- `DataProvider` já tem outras extensões com implementação padrão (`start`, `poll`, `normalizeCallback`); `cacheKeyParams` segue o mesmo padrão.
- A canonicalização continua em `CanonicalHash`; este PRD só muda **o que** entra nela.
- `ProviderCache.lookup`, `store` e `staleFallback` já recebem `operation`; a mudança fica nos chamadores (`NodeHandlers.ExternalCall`, `ProviderBackedValidator`, `ProviderCallbackService`) e em como a chave é montada.
- Para a conclusão assíncrona achar a operação, ela lê a config do nó pelo `nodeId` da chamada, como já faz para `query` e `expect`.
- Migração de dados: políticas em `default` seguem valendo para nós sem `operation`. Quem passar a declarar uma operação precisa cadastrar a política dela.

## 8. Métricas de sucesso

- Dois nós do mesmo provedor com operações diferentes aplicam TTLs diferentes (teste de integração).
- CNPJ formatado e CNPJ só com dígitos produzem HIT na segunda consulta.
- Parâmetro irrelevante à identidade da consulta não derruba a taxa de HIT.
- Nenhuma queda de throughput acima de 10% no teste de carga.

## 9. Perguntas em aberto

- A operação deve ser derivada do adapter (um provedor que expõe várias consultas declara as suas) ou só da config do nó? Proposto: só da config do nó agora, e o adapter passa a declarar as operações válidas quando existir um provedor real com mais de uma.
- Alterar `keyFields` deve invalidar as entradas antigas explicitamente? Proposto: não; elas expiram sozinhas.
- Quem define os `keyFields` de cada operação real (negócio ou engenharia)?
