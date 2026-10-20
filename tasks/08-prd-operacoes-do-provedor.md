# PRD: Operações declaradas pelo provedor (um provedor, várias consultas)

> Depende de `07-prd-cache-operacao-e-chave.md`, **já concluído**: o nó declara `operation`, a política de cache é por `(tenant, provedor, operação)` e o adapter normaliza a chave com `cacheKeyParams`.
> Referência no v0.1: RF-13 (`operationKey`), seção 10.7 (respostas variáveis de provedores externos).

## 1. Introdução

Um provedor expõe várias consultas (a BrasilAPI tem CNPJ, CEP, bancos e outras; BigDataCorp e Unico têm dezenas). Hoje a `operation` é só uma etiqueta de cache:

- O `DataProvider.fetch(config, params)` **não recebe a operação**, então o adapter não sabe qual consulta fazer.
- Nada impede duas consultas diferentes do mesmo provedor, com o mesmo nome de parâmetro (por exemplo `document`), de ficarem na operação `default` e **compartilharem a entrada de cache**: uma devolveria a resposta da outra.
- O grafo não confere se a `operation` do nó existe no provedor, nem se o `query` usa os parâmetros dessa operação.

Este PRD faz da operação um conceito do contrato do provedor desde o início: o adapter declara as suas operações e seus parâmetros, o motor passa a operação em toda chamada, e a ativação rejeita o que o provedor não declara.

## 2. Objetivos

- O adapter declarar quais operações oferece e quais parâmetros cada uma aceita.
- O motor passar a operação para `fetch`, `start`, `poll`, `normalizeCallback` e `cacheKeyParams`.
- Provedor com mais de uma operação exigir `operation` explícita no nó, sem cair em `default`.
- A BrasilAPI passar a ser **um provedor com várias operações** (CNPJ e CEP), validando o desenho na prática.
- Nada do que já funciona quebrar: fluxos, configs de tenant e políticas de cache existentes continuam valendo, migrados para o provedor renomeado.

## 3. Regras para todas as stories

- [ ] `mvn -B verify` verde, sem remover nem enfraquecer testes
- [ ] Migration Flyway nova por story que muda dados ou schema (próxima versão livre); nunca editar as existentes
- [ ] `openapi.yaml` atualizado quando a API mudar
- [ ] Regras do ArchUnit passam
- [ ] Tudo escopado por `tenantId`, com teste de isolamento
- [ ] Nenhum documento em claro em log, `node_execution` ou entrada de cache; a chave continua sendo hash
- [ ] Testes usam provedor falso; nenhuma chamada a API real
- [ ] Os provedores falsos que já existem nos testes continuam funcionando sem alteração

## 4. User Stories

### US-001: Contrato com operações
**Descrição:** Como desenvolvedor de adapter, quero declarar as operações do meu provedor e receber a operação em cada chamada.

**Critérios:**
- [ ] `DataProvider.operations()` devolve as operações do provedor, cada uma com sua chave e seus parâmetros **obrigatórios e opcionais** (`OperationSpec`)
- [ ] A implementação padrão devolve uma única operação legada `default` que aceita qualquer parâmetro, para os provedores falsos dos testes não mudarem
- [ ] `fetch`, `start` (as duas formas), `poll`, `normalizeCallback` e `cacheKeyParams` ganham uma variante que recebe a operação; a implementação padrão delega para a forma atual, então um adapter de uma operação só não precisa mudar
- [ ] O motor passa a chamar sempre as variantes com operação: `NodeHandlers.ExternalCall`, `ProviderBackedValidator`, `ProviderPollService`, `ProviderCallbackService` e `ProviderCache`
- [ ] A operação usada numa chamada assíncrona fica guardada na `provider_call` (coluna nova), para o `poll` e o callback usarem a mesma operação mesmo se o grafo mudar
- [ ] Teste de arquitetura: todo `DataProvider` de produção sobrescreve `operations()` (só os falsos de teste usam o padrão)
- [ ] Teste: um provedor falso com duas operações recebe a operação certa em `fetch`, `start`, `poll` e `normalizeCallback`

### US-002: Resolução e validação da operação
**Descrição:** Como analista de fluxo, quero que um erro de operação ou de parâmetro falhe na ativação, não em produção.

**Critérios:**
- [ ] Provedor com **uma** operação: `operation` no nó é opcional e vale essa operação
- [ ] Provedor com **mais de uma** operação: `operation` é obrigatória; ausente é erro `OPERATION_REQUIRED`, e nunca vale `default`
- [ ] `operation` que o provedor não declara é erro `UNKNOWN_OPERATION` (com a lista das válidas na mensagem)
- [ ] O `query` do nó precisa conter todos os parâmetros obrigatórios da operação (erro `MISSING_PARAMETER`) e só pode usar obrigatórios ou opcionais (erro `UNKNOWN_PARAMETER`)
- [ ] A conferência roda na ativação (`FlowService.activate`), junto da checagem de provedor desabilitado, para `EXTERNAL_CALL` e `VALIDATION` baseado em provedor
- [ ] Teste de ativação para cada erro e para o caso válido; um fluxo ativo antes deste PRD segue executando

### US-003: Migração do que já existe sob `default`
**Descrição:** Como operador, quero que as políticas e entradas de cache já gravadas continuem valendo.

**Critérios:**
- [ ] Migration move `provider_cache_policy` e `provider_cache_entry` com `operation = 'default'` para a operação única do provedor, quando o provedor declara uma só (para `brasilapi-cnpj`: `cnpj`)
- [ ] Políticas de provedores que mantêm a operação legada `default` não são tocadas
- [ ] Se já existir uma linha na operação de destino, a migration mantém a mais recente e apaga a outra (sem violar a unicidade)
- [ ] Teste de migration com dados antes e depois

### US-004: BrasilAPI com várias operações
**Descrição:** Como analista, quero consultar CNPJ e CEP pelo mesmo provedor.

**Critérios:**
- [ ] Provedor `brasilapi` com as operações `cnpj` (parâmetro `cnpj`) e `cep` (parâmetro `cep`); `fetch` escolhe o endpoint pela operação e cada operação normaliza só os campos de decisão
- [ ] `cacheKeyParams` por operação: `cnpj` e `cep` só com dígitos
- [ ] Teste com servidor HTTP simulado (sem chamar a BrasilAPI real): CNPJ e CEP chamam endpoints diferentes, têm entradas de cache separadas e políticas separadas
- [ ] Teste: `document` igual em duas operações nunca compartilha entrada
- [ ] O provedor `brasilapi` convive, nesta story, com o `brasilapi-cnpj` antigo (a remoção é a US-005); o `brasilapi-cnpj` continua passando em todos os seus testes

### US-005: Renomear `brasilapi-cnpj` para `brasilapi`
**Descrição:** Como operador, quero que tudo o que já existe passe para o provedor novo sem edição manual.

**Critérios:**
- [ ] Migration renomeia `brasilapi-cnpj` para `brasilapi` em `provider_config`, `provider_config_audit`, `provider_cache_policy`, `provider_cache_entry` e `provider_call` (mesmo tenant, mesmos campos), para quem já o habilitou; se já houver linha `brasilapi` no mesmo tenant, ela prevalece e a antiga é descartada
- [ ] O provedor e o validator `brasilapi-cnpj` **deixam de existir** neste PRD, sem apelido: o validator passa a ser `brasilapi`, e o nó escolhe a consulta por `operation`
- [ ] Migration reescreve os nós que usam `validatorType: "brasilapi-cnpj"` para `validatorType: "brasilapi"` com `operation: "cnpj"`, em `flow_definition.graph_definition` e no `flow_snapshot` das execuções ainda não terminais (`PENDING`, `RUNNING`, `WAITING`), para nenhuma execução em andamento falhar ao retomar; execuções terminais ficam intactas (histórico)
- [ ] A migration é idempotente e roda dentro de uma transação; um teste a executa duas vezes e confere que o resultado não muda
- [ ] Os testes existentes que citam `brasilapi-cnpj` são atualizados para `brasilapi`, sem remover nenhuma asserção

### US-006: Catálogo e documentação
**Critérios:**
- [ ] A listagem de tipos de nó/validators informa, por provedor, as operações e seus parâmetros
- [ ] `openapi.yaml` regenerado
- [ ] `README.md`: como um adapter declara operações, a regra de `operation` obrigatória, e a mudança de `brasilapi-cnpj` para `brasilapi` (o que a migration reescreve)
- [ ] `tasks/coverage.md` atualizado
- [ ] Teste de carga repetido fora de `/workspace` e registrado em `loadtest/REPORT.md`

## 5. Requisitos funcionais

- FR-1: Toda chamada ao provedor carrega a operação resolvida.
- FR-2: Provedor com mais de uma operação nunca resolve a operação por padrão.
- FR-3: A chave de cache é sempre por `(tenant, provedor, operação, hash)`; operações diferentes nunca compartilham entrada.
- FR-4: Uma chamada assíncrona mantém a operação com que foi criada até terminar.
- FR-5: Ativação rejeita operação ou parâmetro que o provedor não declara.

## 6. Não-objetivos

- Descoberta dinâmica de operações (catálogo remoto do provedor).
- Mais endpoints da BrasilAPI além de CNPJ e CEP (cada novo é só uma operação a declarar).
- Adapters reais de provedores pagos.
- Mudar o formato do `query` ou do `expect` dos nós.

## 7. Considerações técnicas

- O padrão segue o que `DataProvider` já faz com `start`, `poll` e `normalizeCallback`: o método novo tem implementação padrão que delega ao antigo.
- O motor não deve conhecer endpoints: a escolha do endpoint fica dentro do adapter, a partir da operação.
- A conferência na ativação precisa do registro de provedores, que não existe no módulo de domínio do grafo; por isso fica em `FlowService.activate`, como a checagem de provedor desabilitado.
- Renomear o provedor muda a chave usada em `provider_config`, `provider_config_audit`, nas políticas e entradas de cache, nos grafos (`flow_definition`) e nos snapshots de execuções em andamento; a migration da US-005 cobre todos esses lugares e é idempotente.
- Sem apelido, a migração dos grafos é obrigatória: quem aplicar o código sem a migration quebra a ativação e a retomada de fluxos que usam o validator antigo.

## 8. Métricas de sucesso

- Fluxo com um nó CNPJ e um nó CEP do mesmo provedor executa e usa entradas de cache separadas.
- Fluxo existente que usava `brasilapi-cnpj` continua ativo e executando depois da migration, sem edição manual, e uma execução `WAITING` retoma normalmente.
- Ativar um nó com operação inexistente ou parâmetro inválido falha com erro claro.
- Nenhuma queda de throughput acima de 10% no teste de carga.

## 9. Perguntas em aberto

Decididas:
- O apelido `brasilapi-cnpj` **não** é mantido: é removido neste mesmo PRD (a migration da US-005 reescreve os grafos).
- O `OperationSpec` **não** declara os campos da resposta por enquanto; a validação do `expect` na ativação fica para quando houver um caso real.
- O `OperationSpec` declara parâmetros **obrigatórios e opcionais** desde a US-001.

Ainda em aberto: nenhuma.
