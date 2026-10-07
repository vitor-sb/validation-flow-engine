# PRD — Validation Flow Engine

**Versão:** 0.1 — documento inicial para discussão  
**Status:** Rascunho técnico  
**Escopo:** Backend  
**Stack de referência:** Java 17+, Spring Boot, PostgreSQL  
**Origem:** Consolidação das discussões sobre validação dinâmica, sub-flows, histórico, volume transacional e contratos REST.

---

## 1. Resumo executivo

O **Validation Flow Engine** será uma plataforma backend para definir, versionar e executar fluxos de validação antifraude de forma dinâmica. A plataforma deverá atender diferentes tipos de solicitantes e múltiplos contextos de negócio, como:

- empréstimos e crédito;
- abertura ou alteração de conta;
- contratação de cartão, consórcio ou outros serviços;
- transferência de agência;
- operações iniciadas por gerentes;
- operações envolvendo clientes PF, PJ, funcionários, terceiros e prestadores.

O sistema receberá um formulário/contexto em JSON, resolverá o fluxo ativo correspondente e executará um grafo de validações, decisões, chamadas externas, aprovações humanas e sub-flows.

O objetivo é permitir que novos fluxos e alterações de regras sejam publicados sem alterar o código do motor de execução, mantendo:

- versionamento e rastreabilidade;
- execução determinística e auditável;
- extensibilidade para novos validadores;
- suporte a etapas síncronas, assíncronas e humanas;
- isolamento entre execuções;
- evolução modular sem criar um monólito gigantesco.

> **Premissa importante:** o volume apresentado nas conversas — aproximadamente 35–50 milhões de validações/dia e picos de 800–1.500 execuções/s — deve ser tratado como hipótese de dimensionamento inicial, não como requisito confirmado. A capacidade final deve ser validada com métricas reais por produto e cenário.

---

## 2. Problema

Atualmente, cada processo que precisa analisar risco ou validar dados tende a implementar suas próprias regras, integrações, retries, auditoria e tratamento de exceções. Isso gera:

- duplicação de regras entre produtos;
- mudanças que exigem alteração e publicação de código;
- dificuldade para auditar a decisão tomada;
- baixa reutilização de validações;
- acoplamento entre o processo de negócio e integrações externas;
- tratamento inconsistente de timeout, retry e indisponibilidade;
- dificuldade para compor validações comuns em cenários diferentes.

O produto precisa separar:

1. **Definição do fluxo:** o que deve ser executado e em qual ordem;
2. **Execução:** como o grafo é percorrido;
3. **Validadores:** como cada validação concreta é implementada;
4. **Integrações:** como sistemas externos são chamados;
5. **Auditoria:** o que ocorreu e por que uma decisão foi tomada.

---

## 3. Objetivos

### 3.1 Objetivos do produto

1. Permitir criar, validar, versionar, ativar e arquivar fluxos de validação.
2. Resolver automaticamente o fluxo ativo por tenant, tipo de usuário e contexto.
3. Executar grafos com sequência, ramificação, convergência e paralelismo controlado.
4. Permitir que um fluxo chame outro fluxo por meio de um node `SUB_FLOW`.
5. Suportar validadores síncronos, assíncronos e aprovação humana.
6. Produzir uma decisão e uma trilha de auditoria completa.
7. Preservar o histórico mesmo quando o fluxo original for alterado.
8. Suportar alto volume sem obrigar que todo o histórico fique no caminho síncrono.
9. Expor contratos REST estáveis para sistemas chamadores e administração.

### 3.2 Objetivos técnicos

1. Manter o engine stateless do ponto de vista dos nós de aplicação.
2. Isolar o contexto de cada execução.
3. Usar PostgreSQL com JSONB para dados variáveis e relacionamentos fortes.
4. Evitar dependência inicial de Drools ou de um banco de grafos.
5. Separar módulos por responsabilidade e domínio, mesmo que inicialmente sejam entregues em poucos deployables.
6. Permitir evolução futura para serviços separados sem reescrever o domínio.

### 3.3 Fora de escopo inicial

- Frontend/editor visual de fluxos;
- treinamento ou inferência de modelos de machine learning;
- substituição dos sistemas antifraude especializados;
- implementação de todos os validadores de negócio;
- analytics corporativo e data lake;
- motor de regras de inferência geral;
- alteração de projetos existentes da companhia.

---

## 4. Usuários e consumidores

### 4.1 Sistemas consumidores

- canais digitais;
- sistemas de crédito;
- sistemas de cartões e consórcios;
- sistemas operacionais internos;
- portais de gerentes;
- serviços de onboarding/KYC;
- serviços de prevenção à fraude;
- ferramentas internas de administração de fluxos.

### 4.2 Tipos de solicitante

O tipo de solicitante é um atributo de seleção do fluxo, não necessariamente um papel de autenticação:

- `CLIENTE_PF`;
- `CLIENTE_PJ`;
- `GERENTE`;
- `FUNCIONARIO`;
- `TERCEIRO`;
- `PRESTADOR_SERVICO`;
- tipos customizados.

O mesmo tipo pode possuir fluxos diferentes para contextos diferentes. Exemplo:

```text
(CLIENTE_PJ, ABERTURA_CONTA)
(CLIENTE_PJ, EMPRESTIMO)
(CLIENTE_PJ, CONTRATACAO_CARTAO)
(GERENTE, CONTRATACAO_SERVICO_CLIENTE)
```

### 4.3 Multi-tenancy e atributos contextuais

A plataforma deve ser multi-tenant desde o início. Cada empresa consumidora deve
ter seus dados, fluxos, credenciais, integrações e auditoria isolados por um
`tenantId`. O tenant não deve ser inferido apenas de um campo enviado livremente
no JSON; ele deve ser resolvido pelo contexto autenticado do cliente ou por uma
credencial associada à empresa.

O engine recebe `inputData` genérico e não possui campos reservados para papel,
canal, grupo ou tipo. Cada flow define seu próprio contrato de entrada, e o
analista escolhe quais caminhos do payload serão usados nas conditions. Um
cliente pode enviar `type`, outro pode enviar `segment`, `documents` ou qualquer
outro campo necessário ao seu caso de uso.

Nenhum campo deve ser obrigatório para todos os clientes. Cada caso de uso deve
declarar seu contrato de entrada, incluindo campos obrigatórios, opcionais,
tipos e regras de validação.

Exemplo de entrada específica de um flow:

```json
{
  "userType": "GERENTE",
  "context": "VALIDAR_INFORMACOES",
  "inputData": {
    "type": "GERENTE",
    "channel": "VENDAS",
    "groups": ["GERENTES_VENDAS"]
  }
}
```

O engine registra o payload recebido e as conditions avaliadas, mas não
administra o significado dos campos nem a associação de pessoas a grupos.

### 4.4 Grupos de documentos

Documentos podem ser organizados em grupos dentro da definição do fluxo ou da
configuração de um node. O engine não precisa manter uma taxonomia global desses
grupos; ele deve apenas interpretar a configuração versionada que o analista
publicou.

Exemplos:

```text
DOCUMENTOS_CLIENTE_NORMAL
DOCUMENTOS_GERENTE_VENDAS
DOCUMENTOS_DIRETORIA
DOCUMENTOS_GRUPO_FRAUDE
DOCUMENTOS_PJ
```

Um grupo pode conter documentos obrigatórios, alternativos e condicionais. A
escolha do grupo pode ser feita por uma condição do fluxo, por exemplo:

```text
inputData.groups CONTAINS "GRUPO_FRAUDE"
inputData.channel EQUALS "DIRETORIA"
```

O resultado da condição deve ser registrado na execução, permitindo explicar por
que um conjunto de documentos foi solicitado.

---

## 5. Conceitos de domínio

### 5.1 Tenant

Empresa consumidora isolada logicamente no sistema. É a raiz de autorização,
configuração e particionamento lógico dos dados.

### 5.2 Flow Definition

Definição versionada de um fluxo. Contém metadados de seleção e um `graph_definition`.

### 5.3 Flow Version

Versão imutável após ativação. Uma nova alteração gera uma nova versão. Deve existir no máximo uma versão `ACTIVE` por combinação de chave de seleção.

### 5.4 Execution

Instância de execução de uma versão específica do fluxo. Uma execução em andamento permanece vinculada à versão escolhida no início.

### 5.5 Node

Unidade executável do grafo. Tipos iniciais:

- `START`;
- `VALIDATION`;
- `ASYNC_VALIDATION`;
- `EXTERNAL_CALL`;
- `DECISION`;
- `FORK`;
- `JOIN`;
- `SUB_FLOW`;
- `HUMAN_APPROVAL`;
- `END`.

### 5.6 Edge / Transition

Transição entre nodes. Pode possuir condição estruturada e política para caminho não correspondente.

### 5.7 Validator

Implementação Java registrada no runtime por uma chave estável, por exemplo:

```text
VALIDAR_CNPJ
VALIDAR_DOCUMENTOS
CONSULTAR_SERASA
CONSULTAR_BACEN
VALIDAR_COMPLIANCE
CALCULAR_SCORE
```

### 5.8 Execution Context

Contexto isolado de uma execução, contendo dados de entrada, resultados parciais, variáveis derivadas e metadados técnicos. O contexto não deve ser compartilhado entre execuções.

### 5.9 Sub-flow

Node que inicia outro fluxo. O fluxo pai envia um subconjunto do contexto ao filho e recebe um resultado explicitamente mapeado. O filho não altera diretamente o contexto do pai.

### 5.10 Flow Input Data

Payload genérico fornecido pelo consumidor e interpretado conforme o contrato
do flow. Não possui campos semânticos obrigatórios no engine: `type`, `role`,
`channel`, `groups`, `documents` e demais propriedades são apenas exemplos de
dados que um caso de uso pode escolher utilizar.

### 5.11 Document Group

Conjunto de documentos e regras de obrigatoriedade declarado no fluxo ou na
configuração do validator. Pode ser selecionado por condições como `IN` e
`CONTAINS`, sem exigir um cadastro global de grupos no engine.

### 5.12 Metadados

Metadados são informações descritivas e administrativas usadas para identificar,
catalogar, selecionar e governar um fluxo ou node. Eles não executam a validação
por si só. A lógica executável fica no `graphDefinition`, nas configurações dos
nodes e nas condições das transições.

#### Metadados da definição do fluxo

| Campo           | Tipo           | Obrigatório | Finalidade                                                                     | Exemplo                            |
| --------------- | -------------- | ----------: | ------------------------------------------------------------------------------ | ---------------------------------- |
| `tenantId`      | `String`       |         Sim | Empresa proprietária e limite de isolamento do fluxo.                          | `empresa-xpto`                     |
| `flowKey`       | `String`       |         Sim | Identificador técnico estável do fluxo. Não deve mudar entre versões.          | `EMPRESTIMO_PJ`                    |
| `version`       | `Integer`      |      Gerado | Identifica a versão imutável da definição.                                     | `3`                                |
| `status`        | `Enum`         |      Gerado | Estado administrativo do fluxo.                                                | `DRAFT`, `ACTIVE`, `ARCHIVED`      |
| `userType`      | `String`       |         Sim | Tipo de solicitante ao qual o fluxo se aplica.                                 | `CLIENTE_PJ`                       |
| `context`       | `String`       |         Sim | Processo ou operação que dispara o fluxo.                                      | `EMPRESTIMO`                       |
| `displayName`   | `String`       |         Sim | Nome amigável para consulta e administração.                                   | `Empréstimo para cliente PJ`       |
| `description`   | `String`       |         Não | Explica o objetivo, o escopo e o resultado esperado do fluxo.                  | `Valida risco para empréstimos PJ` |
| `tags`          | `List<String>` |         Não | Classifica o fluxo para busca e organização.                                   | `["credito", "antifraude", "pj"]`  |
| `product`       | `String`       |         Não | Produto bancário relacionado.                                                  | `CREDITO_EMPRESARIAL`              |
| `channel`       | `String`       |         Não | Canal de origem quando o mesmo contexto possui políticas diferentes por canal. | `APP`, `INTERNET_BANKING`          |
| `priority`      | `Integer`      |         Não | Prioridade de seleção quando houver mais de um critério aplicável no futuro.   | `100`                              |
| `schemaVersion` | `String`       |         Sim | Versão do formato do grafo, diferente da versão de negócio do fluxo.           | `1`                                |
| `createdBy`     | `String`       |      Gerado | Identidade responsável pela criação.                                           | `user-123`                         |
| `createdAt`     | `Instant`      |      Gerado | Data/hora de criação.                                                          | `2026-10-01T12:00:00Z`             |
| `updatedAt`     | `Instant`      |      Gerado | Data/hora da última alteração.                                                 | `2026-10-01T12:30:00Z`             |
| `activatedAt`   | `Instant`      |      Gerado | Data/hora em que a versão foi ativada.                                         | `2026-10-01T13:00:00Z`             |

Campos como `product`, `channel` e `priority` só devem participar da resolução
do fluxo quando essa regra estiver formalmente definida. O `tenantId` sempre
faz parte da chave de isolamento; no MVP, a chave mínima de seleção pode ser
`(tenantId, userType, context)`. Grupos, papéis e atributos do sujeito devem
ser avaliados dentro do fluxo, por condições explícitas.

#### Metadados de um node

Cada node também deve possuir informações suficientes para que uma pessoa ou
ferramenta consiga entender o ponto sem ler a implementação Java:

| Campo           | Tipo           | Finalidade                                                   | Exemplo                          |
| --------------- | -------------- | ------------------------------------------------------------ | -------------------------------- |
| `id`            | `String`       | Identificador técnico único dentro do grafo.                 | `validate_documents`             |
| `type`          | `Enum`         | Define o comportamento estrutural do node.                   | `VALIDATION`                     |
| `name`          | `String`       | Nome amigável exibido em documentação ou editor.             | `Validar documentos`             |
| `description`   | `String`       | Explica o que o node valida, quais dados usa e o que produz. | `Confere CNPJ e contrato social` |
| `validatorType` | `String`       | Identifica a Strategy responsável pela validação.            | `VALIDAR_DOCUMENTOS`             |
| `tags`          | `List<String>` | Classifica o node por domínio, criticidade ou integração.    | `["documentos", "kyc"]`          |
| `config`        | `Object`       | Parâmetros executáveis, timeout, retry e mappings.           | `requiredDocs`, `timeout`        |
| `owner`         | `String`       | Área ou equipe responsável pela regra.                       | `ONBOARDING`                     |
| `criticality`   | `Enum`         | Impacto operacional de falha ou indisponibilidade.           | `HIGH`                           |

Exemplo de node documentado:

```json
{
  "id": "validate_documents",
  "type": "VALIDATION",
  "name": "Validar documentos societários",
  "description": "Verifica a existência, validade e consistência do CNPJ e do contrato social.",
  "validatorType": "VALIDAR_DOCUMENTOS",
  "tags": ["documentos", "kyc"],
  "owner": "ONBOARDING",
  "criticality": "HIGH",
  "config": {
    "requiredDocs": ["CNPJ", "CONTRATO_SOCIAL"],
    "timeout": "PT30S"
  },
  "transitions": [
    {
      "targetNodeId": "credit_analysis",
      "condition": {
        "operator": "EQUALS",
        "field": "context.documents.status",
        "value": "VALID"
      }
    }
  ]
}
```

#### Metadados não substituem regras

```text
description       -> explica o que o ponto faz
validatorType     -> aponta qual implementação deve executar
config            -> informa como executar
condition         -> decide se a transição será seguida
metadata/tags     -> ajudam a catalogar e governar
```

Por exemplo, `description: "Valida o CNPJ"` não executa uma validação. O
`validatorType: "VALIDAR_CNPJ"` seleciona a implementação, e a entrada
necessária deve estar definida no contrato do validator.

---

## 6. Requisitos funcionais

### RF-01 — Cadastro de fluxo

O sistema deve permitir cadastrar uma definição com:

- tenant proprietário;
- chave do fluxo;
- versão;
- tipo de usuário;
- contexto;
- contrato de entrada, com campos obrigatórios e opcionais do caso de uso;
- nome e descrição;
- grafo;
- metadados;
- responsável pela criação.

### RF-02 — Validação estrutural

Antes de ativar um fluxo, o sistema deve validar:

- existência de um node inicial;
- existência de pelo menos um caminho até `END`;
- referências de edges para nodes existentes;
- ausência de ciclos quando o fluxo for classificado como DAG;
- ausência de nodes inalcançáveis;
- compatibilidade dos tipos de node;
- existência dos validadores referenciados;
- configuração de timeout e retry;
- validade de condições e mappings;
- limites de profundidade para sub-flows.

### RF-03 — Versionamento

- Fluxos ativos não devem ser editados de forma destrutiva.
- Alterações devem gerar nova versão.
- Apenas uma versão pode estar ativa para cada combinação de tenant e critérios
  de seleção equivalentes.
- A ativação deve ser atômica.
- Execuções existentes devem continuar usando o snapshot da versão inicial.
- Versões arquivadas devem permanecer consultáveis para auditoria.

### RF-04 — Seleção de fluxo

O engine deve resolver a versão ativa por:

1. `flow_key`, quando informado explicitamente; ou
2. combinação de `tenant_id` + `user_type` + `context`.

Grupos, papéis, canais e atributos do sujeito não devem criar implicitamente
novas regras de seleção. Quando precisarem alterar o caminho, devem ser
avaliados por conditions dentro do grafo. Empates entre versões para a mesma
chave de seleção devem ser rejeitados como configuração inválida, e não
resolvidos por ordem incidental de banco ou data de criação.

### RF-05 — Execução do grafo

O engine deve:

1. criar a execução;
2. carregar a definição versionada;
3. inicializar o contexto;
4. identificar os nodes elegíveis;
5. executar nodes sequenciais ou paralelos;
6. avaliar transições;
7. persistir status e resultados;
8. finalizar com decisão, falha, cancelamento ou espera.

### RF-06 — Condições

As condições devem ser armazenadas em formato estruturado, evitando expressões livres executáveis. O MVP deve suportar:

- `EQUALS`;
- `NOT_EQUALS`;
- `GREATER_THAN`;
- `GREATER_THAN_OR_EQUALS`;
- `LESS_THAN`;
- `LESS_THAN_OR_EQUALS`;
- `CONTAINS`;
- `IN`;
- `EXISTS`;
- `AND`;
- `OR`;
- `NOT`.

Exemplos de condições para dados genéricos recebidos pelo fluxo:

```text
inputData.groups CONTAINS "GRUPO_FRAUDE"
inputData.type IN ["GERENTE", "DIRETOR"]
inputData.channel EQUALS "VENDAS"
inputData.segment IN ["PREMIUM", "CORPORATE"]
```

`CONTAINS` deve ser usado quando o campo avaliado for uma coleção e `IN` quando
o valor avaliado for comparado com uma lista permitida. O contrato deve definir
o comportamento para campo ausente, valor nulo e tipo incompatível; por padrão,
esses casos não devem ser convertidos silenciosamente em uma condição verdadeira.

Antes de iniciar o grafo, o sistema deve validar o contrato de entrada do fluxo.
Campos obrigatórios ausentes devem gerar erro de validação de entrada. Campos
opcionais ausentes devem permanecer ausentes no contexto e só podem influenciar
o caminho quando uma condição explícita tratar essa situação, por exemplo:

```text
inputData.groups EXISTS
inputData.groups CONTAINS "GRUPO_FRAUDE"
```

O avaliador pode ser implementado em Java puro no primeiro ciclo. JSONLogic ou outro DSL estruturado pode ser adotado quando houver necessidade de edição externa ou maior composição.

> SpEL e Drools não são requisitos do MVP. SpEL só deve ser introduzido se houver necessidade real de expressões textuais controladas. Drools deve ser considerado apenas se surgirem regras de inferência, encadeamento de fatos ou governança específica que justifique a complexidade.

### RF-07 — Execução assíncrona

O sistema deve suportar nodes que:

- chamam um sistema externo;
- aguardam callback;
- usam polling;
- expiram por timeout;
- executam retry;
- entram em dead-letter para tratamento operacional.

### RF-08 — Aprovação humana

O fluxo pode pausar em `HUMAN_APPROVAL`. A execução deve guardar:

- motivo da aprovação;
- responsável ou grupo esperado;
- prazo;
- decisão;
- observação;
- evidências recebidas.

### RF-09 — Sub-flows

O node `SUB_FLOW` deve suportar:

- referência ao fluxo filho;
- seleção da versão ativa ou versão explicitamente fixada;
- `input_mapping`;
- `output_mapping`;
- política de falha;
- timeout;
- retry;
- limite de profundidade;
- detecção de ciclos de composição.

Exemplo:

```json
{
  "type": "SUB_FLOW",
  "config": {
    "flowKey": "ANALISE_CREDITO_PJ",
    "inputMapping": {
      "cnpj": "$.context.cnpj"
    },
    "outputMapping": {
      "scoreCredito": "$.result.score",
      "riskLevel": "$.result.riskLevel"
    },
    "onFailure": "FAIL_PARENT",
    "maxDepth": 5
  }
}
```

`$.result.score` é uma expressão JSONPath que indica a origem do valor no resultado do fluxo filho.

### RF-10 — Histórico

O sistema deve manter:

- execução;
- versão executada;
- entrada original;
- contexto acumulado;
- resultado final;
- status;
- cada tentativa de node;
- snapshots de entrada e saída;
- erros;
- transições avaliadas;
- eventos de auditoria.

### RF-11 — Idempotência

O endpoint de execução deve aceitar uma chave de idempotência fornecida pelo consumidor. Requisições repetidas com a mesma chave e escopo devem retornar a execução original ou uma resposta equivalente, sem duplicar a operação.

### RF-12 — Cancelamento e retomada

Execuções pausadas ou em andamento devem poder ser canceladas conforme a política do fluxo. Execuções pausadas por aprovação humana ou callback devem poder ser retomadas com dados de continuação validados.

### RF-13 — Cache de respostas de provedores

O sistema deve permitir reutilizar respostas de provedores externos quando o dado possuir uma validade de negócio conhecida. O objetivo é reduzir custo, latência e dependência de APIs pagas, sem comprometer regras que exigem consulta em tempo real.

Cada tipo de consulta deve possuir uma política de cache versionada, definida por pelo menos:

- `providerKey`;
- `operationKey`, como `CONSULTAR_SCORE` ou `CONSULTAR_DOCUMENTO`;
- `dataCategory`, que classifica a estabilidade e a necessidade de atualização do dado;
- `customCategoryKey`, quando `dataCategory=CUSTOM`;
- `ttl`, que define a validade máxima da resposta;
- `cacheable`, indicando se a operação pode ser reutilizada;
- `staleAllowed`, indicando se uma resposta vencida pode ser usada em contingência;
- `keyFields`, que define quais dados identificam o sujeito e o contexto da consulta;
- `sensitivity`, para determinar retenção, criptografia e acesso;
- política de invalidação ou atualização antecipada.

#### Categorias de validade

O TTL deve ser derivado de uma categoria de validade, e não ficar espalhado nas implementações dos validadores. A categoria representa a volatilidade e a necessidade de atualização do dado; sensibilidade, risco e exigência regulatória são dimensões independentes e podem reduzir a validade.

| Categoria           | Característica                                                  | Faixa inicial sugerida |
| ------------------- | --------------------------------------------------------------- | ---------------------: |
| `REAL_TIME`         | Dado muda frequentemente ou a decisão exige a situação atual    |   Sem cache ou minutos |
| `HIGH_VOLATILITY`   | Dado pode mudar em dias ou semanas                              |    Horas a poucos dias |
| `MEDIUM_VOLATILITY` | Dado relativamente estável, mas sujeito a alterações relevantes |      Semanas a 3 meses |
| `LOW_VOLATILITY`    | Dado estável e alterado principalmente por evento cadastral     |           3 a 12 meses |
| `CUSTOM`            | Categoria específica de uma operação ou domínio                 | Definida pela política |

Quanto maior a estabilidade do dado, maior pode ser o período de reutilização, desde que a política de negócio permita. A categoria não deve ser interpretada como uma autorização para manter qualquer informação por longo período: requisitos legais, privacidade, risco e eventos de invalidação sempre prevalecem.

Uma categoria `CUSTOM` deve ser usada quando as categorias padrão não representarem adequadamente o dado. Ela precisa ter nome, descrição, TTL, unidade, justificativa, responsável, data de revisão e política de invalidação. Não deve ser permitido criar uma categoria customizada apenas informando um número de dias sem governança. Enquanto a categoria não estiver aprovada ou quando sua configuração estiver inválida, a operação deve ser rejeitada ou submetida a uma política padrão mais restritiva.

Exemplos iniciais, sujeitos à validação jurídica, regulatória e de negócio:

| Tipo de dado                        |        Validade inicial sugerida | Observação                                                              |
| ----------------------------------- | -------------------------------: | ----------------------------------------------------------------------- |
| Score de crédito                    |     `MEDIUM_VOLATILITY`, 3 meses | Pode variar por produto, risco, provedor e evento relevante             |
| Documento de identificação, como RG |        `LOW_VOLATILITY`, 6 meses | Deve ser invalidado se houver alteração cadastral ou suspeita de fraude |
| Status cadastral                    |  `HIGH_VOLATILITY`, configurável | Pode exigir atualização diária ou consulta em tempo real                |
| Lista de sanções ou restrições      | `REAL_TIME` ou `HIGH_VOLATILITY` | A validade deve seguir a exigência do domínio e da fonte                |

O TTL não deve ser codificado diretamente no engine. Ele deve ser resolvido por uma política que possa considerar categoria, operação, provedor, produto, canal, tipo de usuário, evento de negócio e versão do contrato. A regra efetiva deve usar o menor prazo entre a categoria, a exigência regulatória e as restrições específicas do contexto.

O cache deve diferenciar:

- **cache hit válido:** resposta encontrada e ainda dentro do TTL;
- **cache miss:** não existe resposta reutilizável;
- **cache expirado:** existe resposta, mas a validade terminou;
- **cache bypass:** a política exige consulta nova;
- **erro do provedor:** falha técnica sem transformar automaticamente a resposta antiga em válida;
- **fallback controlado:** uso de resposta vencida somente quando a política permitir e isso for registrado na decisão.

Respostas vencidas não devem ser usadas silenciosamente. Quando o fallback for permitido, a execução deve registrar a idade do dado, a política aplicada e o motivo pelo qual a consulta nova não foi utilizada.

O cache deve ser persistido para sobreviver a reinicializações e permitir auditoria. Para milhões de registros com expiração longa, a fonte principal deve ser um armazenamento persistente, como PostgreSQL, com índices adequados, particionamento quando necessário e processo de limpeza/arquivamento baseado em `valid_until`. Redis ou Data Grid pode ser adicionado como camada de aceleração para entradas quentes, com limite de memória, política de eviction e TTL próprio; não deve ser a única fonte de verdade para esse histórico de respostas. O armazenamento deve considerar criptografia, controle de acesso, retenção e mascaramento de dados pessoais.

### RF-14 — Isolamento por empresa e condições de fluxo

O sistema deve:

- associar toda definição, execução, integração, cache e auditoria a um
  `tenantId`;
- impedir que uma empresa consulte ou reutilize fluxos, documentos, cache ou
  resultados de outra;
- aceitar dados genéricos em `inputData`, sem reservar campos de negócio no
  engine;
- permitir que o analista use esses dados em condições do grafo;
- permitir declarar grupos de documentos na configuração do fluxo ou do
  validator;
- suportar documentos obrigatórios, alternativos e condicionais;
- registrar na execução as condições avaliadas e os documentos efetivamente
  aplicados;
- impedir que uma alteração na definição do fluxo modifique retroativamente uma
  execução já iniciada.

Exemplo de fluxo:

```text
tenant: empresa-xpto
contexto: VALIDAR_INFORMACOES
condição: inputData.groups CONTAINS "GERENTES_VENDAS"
resultado: solicitar DOCUMENTOS_GERENTE_VENDAS
```

O grupo de documentos deve ser parte do snapshot da versão do fluxo ou da
configuração do node. Não é necessário um cadastro global de grupos para o MVP.

---

## 7. Arquitetura modular proposta

### 7.1 Princípio

A recomendação é começar com um **modular monolith bem delimitado** ou com poucos deployables, e não com um monólito acoplado nem com dezenas de microserviços.

O código deve ser organizado por módulos de negócio, com contratos internos explícitos. A separação física em serviços ocorrerá somente quando houver necessidade operacional, de escala ou de ciclo de vida independente.

### 7.2 Módulos

#### 1. Flow Catalog

Responsável por:

- CRUD de fluxos;
- versionamento;
- ativação e arquivamento;
- validação estrutural do grafo;
- resolução da versão ativa.

#### 2. Execution Orchestrator

Responsável por:

- iniciar e controlar execuções;
- manter estados;
- selecionar nodes elegíveis;
- coordenar forks e joins;
- tratar pausa, retomada, timeout e cancelamento.

#### 3. Graph Runtime

Responsável por:

- interpretar `graph_definition`;
- avaliar edges;
- impedir ciclos inválidos;
- controlar profundidade de sub-flows;
- fornecer o próximo conjunto de nodes.

#### 4. Validator Runtime

Responsável por:

- registry de strategies;
- contrato comum de validadores;
- timeout e retry locais;
- validação de configuração;
- versionamento de capabilities.

Contrato conceitual:

```java
public interface ValidatorStrategy {
    String key();
    ValidationResult execute(ValidationInput input);
}
```

#### 5. Integration Gateway

Responsável por encapsular chamadas a bureaus, serviços internos e parceiros. O engine não deve conhecer detalhes de HTTP, SOAP, autenticação ou payload de cada integração.

#### 6. Provider Cache and Policy

Responsável por resolver políticas de reutilização, consultar o cache persistido, controlar TTL e invalidar ou atualizar respostas de provedores. Esse módulo deve ficar separado do client HTTP e do engine para que a decisão de usar cache seja explícita, observável e testável.

Contrato conceitual:

```java
public interface ProviderResponseCache {
    Optional<CachedProviderResponse> find(CacheLookup lookup);

    void save(CacheEntry entry);

    void invalidate(CacheKey key, InvalidationReason reason);
}
```

O `Integration Gateway` deve consultar esse módulo antes de chamar o provedor. Em caso de `cache miss`, `cache expirado` ou `cache bypass`, o gateway chama o provedor, normaliza a resposta e salva o resultado conforme a política aplicável.

#### 7. Condition Runtime

Responsável por avaliar condições estruturadas. Deve ser determinístico, seguro, testável e observável.

#### 8. Async Coordination

Responsável por callbacks, polling, retries, timeouts, filas e dead-letter.

#### 9. Human Decision

Responsável por estados de aprovação, expiração, delegação e retomada.

#### 10. Execution History

Responsável por persistir e consultar histórico operacional e de auditoria.

#### 11. Query and Reporting

Responsável por consultas paginadas, filtros operacionais e futuras projeções para analytics. Não deve sobrecarregar o caminho de decisão.

#### 12. Platform

Responsável por autenticação, autorização, isolamento de tenant, observabilidade,
configuração, health checks, correlação, rate limit e tratamento de erros.

### 7.3 Estrutura de pacotes

Uma organização compatível com arquitetura hexagonal:

```text
com.prevention.fraud.validationflow/
├── domain/
│   ├── flow/
│   ├── execution/
│   ├── node/
│   └── shared/
├── application/
│   ├── flowcatalog/
│   │   ├── ports/input/
│   │   ├── ports/output/
│   │   └── usecases/
│   ├── execution/
│   │   ├── ports/input/
│   │   ├── ports/output/
│   │   └── usecases/
│   ├── validator/
│   └── condition/
├── adapter/
│   ├── in/rest/
│   └── out/
│       ├── postgres/repository/
│       ├── messaging/
│       └── integrations/
└── config/
    └── observability/
```

Regras de dependência:

- `domain` não depende de nenhuma outra camada;
- `application` depende apenas de `domain`; as interfaces (ports) ficam em `application/**/ports`;
- `adapter` implementa ou consome as ports e pode depender de `application` e `domain`; `application` nunca depende de `adapter`;
- controllers ficam em `adapter/in/rest`; repositories e clients em `adapter/out`;
- `config` é a única pasta que conhece todas as camadas, para montar os beans do Spring.

---

## 8. Persistência e modelo de dados

### 8.1 Decisão inicial

Usar PostgreSQL como banco transacional principal, com JSONB para estruturas variáveis.

Motivos:

- transações ACID para ativação e versionamento;
- relacionamentos fortes entre fluxo, execução e node;
- índices relacionais e JSONB;
- consultas operacionais e agregações;
- menor custo operacional que adicionar um banco de grafos;
- flexibilidade semelhante a documento para inputs e resultados.

MongoDB pode ser reavaliado se o volume confirmado exigir outra estratégia de particionamento/sharding ou se o padrão de acesso se tornar quase exclusivamente lookup por ID.

### 8.1.1 Primário e réplicas de leitura

Como o `execution-app` tende a receber muito mais tráfego que a gestão, o
PostgreSQL deve permitir separar caminhos de escrita e leitura:

- **primário:** ativações, alterações de fluxo, início de execução, atualizações
  de estado, auditoria e gravação de resultados;
- **réplicas de leitura:** consultas de status, listagens, histórico, relatórios
  e leituras que tolerem eventual atraso de replicação.

O roteamento deve ser definido por caso de uso, e não apenas por método HTTP.
Uma consulta que precisa enxergar imediatamente uma ativação ou uma mudança de
estado deve ler do primário. Consultas operacionais e analíticas podem usar
réplicas quando a consistência eventual for aceitável.

Pontos importantes:

- a ativação de uma versão e a resolução inicial da execução devem respeitar
  consistência forte;
- o atraso da réplica deve ser medido e exposto em observabilidade;
- falha ou atraso de uma réplica não pode interromper escritas no primário;
- pool de conexões e transações devem impedir que uma leitura crítica seja
  direcionada acidentalmente a uma réplica;
- réplicas aumentam capacidade de leitura e disponibilidade, mas não resolvem
  sozinhas o gargalo de escrita;
- particionamento, retenção e projeções de consulta devem ser avaliados antes
  de criar muitas réplicas.

### 8.2 Entidades principais

```text
flow_definition
flow_execution
node_execution
execution_audit_log
execution_callback
human_approval
idempotency_key
```

### 8.3 `flow_definition`

Campos mínimos:

```text
id
tenant_id
flow_key
version
status
user_type
context
subject_type nullable
role nullable
group_key nullable
display_name
description
graph_definition JSONB
metadata JSONB
created_by
created_at
updated_at
activated_at
```

Restrições:

- `UNIQUE(flow_key, version)`;
- índice único parcial para uma versão ativa por tenant e seletor;
- grafo imutável após ativação.

### 8.4 `flow_execution`

Campos mínimos:

```text
id
tenant_id
flow_definition_id
flow_version
parent_execution_id nullable
parent_node_id nullable
status
current_node_ids
input_data JSONB
context_data JSONB
result JSONB
error_info JSONB
initiated_by
correlation_id
idempotency_key
started_at
completed_at
expires_at
created_at
updated_at
```

Os campos JSONB permitem que fluxos diferentes produzam resultados diferentes sem alterar o schema relacional.

### 8.5 `node_execution`

Cada tentativa deve ser rastreável:

```text
id
tenant_id
flow_execution_id
node_id
node_type
validator_type
status
attempt
input_snapshot JSONB
output_data JSONB
error_info JSONB
started_at
completed_at
```

### 8.6 `provider_cache_entry`

Representa uma resposta normalizada de provedor que pode ser reutilizada dentro de uma política de validade.

Campos mínimos:

```text
id
tenant_id
provider_key
operation_key
data_category
custom_category_key nullable
subject_key_hash
request_fingerprint
contract_version
normalized_result JSONB
provider_evidence JSONB
fetched_at
valid_until
stale_until nullable
status
invalidation_reason nullable
created_at
updated_at
```

Recomendações:

- não persistir documentos pessoais em texto puro quando um identificador hash ou tokenizado for suficiente para a chave;
- incluir no `request_fingerprint` somente parâmetros que alterem o resultado;
- incluir produto, canal ou contexto quando a resposta depender desses fatores;
- versionar o contrato normalizado para evitar reutilizar uma resposta incompatível com uma nova interpretação;
- usar `valid_until` para o TTL de negócio e `stale_until` apenas quando houver fallback controlado;
- registrar a origem, a data de consulta e a evidência necessária para auditoria;
- aplicar criptografia, controle de acesso e retenção compatíveis com a sensibilidade do dado;
- impedir que uma resposta de um provedor seja reutilizada como se fosse resposta de outro.

O PostgreSQL pode ser a fonte persistente inicial. Para reduzir latência entre múltiplas instâncias, uma camada distribuída de cache pode ser adicionada posteriormente, desde que a política de validade e a persistência/auditoria permaneçam explícitas. Cache local de processo deve ser tratado apenas como otimização secundária, pois não é compartilhado entre pods e pode ser perdido em uma reinicialização.

### 8.7 Histórico em alto volume

O caminho síncrono deve persistir apenas o necessário para consistência da decisão. Eventos detalhados e projeções de consulta podem ser publicados de forma assíncrona.

Estratégias a validar em benchmark:

- particionamento por data de `created_at` ou `occurred_at`;
- retenção diferenciada para dados operacionais e auditoria;
- réplica de leitura;
- arquivamento de execuções antigas;
- fila para gravação de eventos detalhados;
- projeção em mecanismo especializado para consultas analíticas.

Não se deve dimensionar a tabela multiplicando automaticamente cada execução por todos os nodes sem medir o nível de auditoria realmente requerido.

---

## 9. Contrato do grafo

Formato recomendado:

```json
{
  "schemaVersion": 1,
  "startNodeId": "start",
  "nodes": {
    "start": {
      "id": "start",
      "type": "START",
      "transitions": [{ "targetNodeId": "validate_docs" }]
    },
    "validate_docs": {
      "id": "validate_docs",
      "type": "VALIDATION",
      "config": {
        "validatorType": "VALIDAR_DOCUMENTOS",
        "params": {
          "requiredDocs": ["CNPJ", "CONTRATO_SOCIAL"]
        },
        "timeout": "PT30S",
        "retryPolicy": {
          "maxAttempts": 3,
          "backoff": "EXPONENTIAL"
        }
      },
      "transitions": [
        {
          "targetNodeId": "credit_check",
          "condition": {
            "operator": "EQUALS",
            "field": "context.documents.status",
            "value": "VALID"
          }
        }
      ]
    }
  }
}
```

Para o MVP, recomenda-se armazenar nodes em mapa por ID e transitions dentro de cada node. Isso facilita lookup, snapshot e validação. Um formato explícito de `edges` pode ser adicionado caso ferramentas externas precisem manipular o grafo como entidade independente.

---

## 10. API REST inicial

Base: `/api/v1`

### 10.0 Separação entre execução e gestão

Embora o produto possua dois públicos e responsabilidades diferentes, o MVP não
precisa iniciar com dois serviços independentes. A recomendação é um backend
modular com duas superfícies de API separadas:

1. **Execution API:** consumida pelos sistemas de negócio para iniciar e
   consultar validações;
2. **Flow Management API:** usada por analistas ou ferramentas administrativas
   para criar, validar, versionar, ativar e arquivar fluxos.

Essa separação deve existir nos módulos, contratos, permissões e auditoria,
mesmo que as duas APIs sejam implantadas no mesmo serviço inicialmente. O
frontend de gestão não faz parte do MVP. Quando for criado, deverá consumir a
Flow Management API e nunca acessar o banco diretamente.

Cada consumidor deve possuir uma credencial associada a um tenant. A credencial
deve carregar ou resolver:

- `tenantId`;
- identidade do sistema consumidor;
- escopos autorizados;
- fluxos ou operações permitidos, quando necessário;
- limites de rate limit e auditoria.

Exemplos de escopos:

```text
validation:execute
validation:read
flow:read
flow:write
flow:activate
```

Uma API key de um sistema consumidor não deve permitir criar ou ativar fluxos
apenas por estar autenticada. A autorização deve verificar tenant, escopo,
operação e, quando aplicável, o conjunto de fluxos permitido.

### 10.1 Gestão de fluxos

| Método  | Endpoint                   | Objetivo                       |
| ------- | -------------------------- | ------------------------------ |
| `POST`  | `/flows`                   | Criar fluxo em `DRAFT`         |
| `GET`   | `/flows`                   | Listar com filtros e paginação |
| `GET`   | `/flows/{flowId}`          | Consultar definição completa   |
| `PUT`   | `/flows/{flowId}`          | Criar/atualizar versão         |
| `PATCH` | `/flows/{flowId}/activate` | Ativar versão atomicamente     |
| `PATCH` | `/flows/{flowId}/archive`  | Arquivar versão                |
| `POST`  | `/flows/validate`          | Validar grafo sem persistir    |

### 10.2 Execuções

| Método | Endpoint                           | Objetivo                     |
| ------ | ---------------------------------- | ---------------------------- |
| `POST` | `/executions`                      | Iniciar execução             |
| `GET`  | `/executions/{executionId}`        | Consultar status e resultado |
| `GET`  | `/executions`                      | Listar execuções             |
| `GET`  | `/executions/{executionId}/nodes`  | Consultar histórico de nodes |
| `POST` | `/executions/{executionId}/resume` | Retomar execução pausada     |
| `POST` | `/executions/{executionId}/cancel` | Cancelar execução            |

### 10.3 Capabilities

| Método | Endpoint                    | Objetivo                        |
| ------ | --------------------------- | ------------------------------- |
| `GET`  | `/node-types`               | Listar validadores disponíveis  |
| `GET`  | `/node-types/{nodeTypeKey}` | Consultar schema e capabilities |

### 10.4 Operação

| Método | Endpoint           | Objetivo           |
| ------ | ------------------ | ------------------ |
| `GET`  | `/health`          | Health agregado    |
| `GET`  | `/actuator/health` | Liveness/readiness |

### 10.5 Exemplo de início

```http
POST /api/v1/executions
Idempotency-Key: emprestimo-123456-v1
Content-Type: application/json
```

```json
{
  "userType": "CLIENTE_PJ",
  "context": "EMPRESTIMO",
  "correlationId": "origin-abc-123",
  "inputData": {
    "cnpj": "12345678000100",
    "requestedAmount": 500000,
    "purpose": "CAPITAL_GIRO"
  }
}
```

Resposta inicial:

```json
{
  "executionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "flowKey": "EMPRESTIMO_PJ",
  "flowVersion": 3,
  "status": "RUNNING",
  "startedAt": "2026-04-10T15:30:00Z"
}
```

### 10.6 Exemplos de DTOs Java

Os DTOs abaixo representam apenas o contrato da API. Eles não devem ser usados
diretamente como entidades de domínio. O adapter REST converte DTOs em commands
da aplicação, e os responses do domínio são convertidos novamente para DTOs.

#### Criar fluxo

```java
public record CreateFlowRequest(
        @NotBlank String flowKey,
        @NotBlank String userType,
        @NotBlank String context,
        @NotBlank String displayName,
        String description,
        @NotNull GraphDefinitionRequest graphDefinition,
        Map<String, Object> metadata
) {}
```

```java
public record CreateFlowResponse(
        UUID flowId,
        String flowKey,
        int version,
        String status,
        String userType,
        String context,
        Instant createdAt
) {}
```

#### Definição do grafo

```java
public record GraphDefinitionRequest(
        @NotBlank String schemaVersion,
        @NotBlank String startNodeId,
        @NotEmpty Map<String, NodeDefinitionRequest> nodes
) {}

public record NodeDefinitionRequest(
        @NotBlank String id,
        @NotNull NodeType type,
        String name,
        Map<String, Object> config,
        List<TransitionRequest> transitions
) {}

public record TransitionRequest(
        @NotBlank String targetNodeId,
        ConditionRequest condition
) {}
```

Os enums e contratos mínimos podem ser:

```java
public enum NodeType {
    START,
    VALIDATION,
    ASYNC_VALIDATION,
    EXTERNAL_CALL,
    DECISION,
    FORK,
    JOIN,
    SUB_FLOW,
    HUMAN_APPROVAL,
    END
}

public record ConditionRequest(
        ConditionOperator operator,
        String field,
        Object value,
        List<ConditionRequest> conditions
) {}

public enum ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    CONTAINS,
    IN,
    EXISTS,
    AND,
    OR,
    NOT
}
```

#### Iniciar execução

```java
public record StartExecutionRequest(
        @NotBlank String userType,
        @NotBlank String context,
        String flowKey,
        String correlationId,
        @NotNull Map<String, Object> inputData
) {}
```

```java
public record StartExecutionResponse(
        UUID executionId,
        UUID flowDefinitionId,
        String flowKey,
        int flowVersion,
        ExecutionStatus status,
        Instant startedAt
) {}

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    WAITING_ASYNC,
    WAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
```

#### Consulta de execução

```java
public record ExecutionResponse(
        UUID executionId,
        UUID parentExecutionId,
        String flowKey,
        int flowVersion,
        ExecutionStatus status,
        Map<String, Object> inputData,
        Map<String, Object> contextData,
        Map<String, Object> result,
        ErrorResponse error,
        Instant startedAt,
        Instant completedAt,
        List<NodeExecutionResponse> nodes
) {}

public record NodeExecutionResponse(
        String nodeId,
        NodeType nodeType,
        String validatorType,
        String status,
        int attempt,
        Map<String, Object> outputData,
        Instant startedAt,
        Instant completedAt
) {}

public record ErrorResponse(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details
) {}
```

#### Retomar ou cancelar execução

```java
public record ResumeExecutionRequest(
        @NotNull Map<String, Object> data,
        String decidedBy,
        String comment
) {}

public record CancelExecutionRequest(
        @NotBlank String reason
) {}
```

#### Regras para os DTOs

- DTOs REST podem usar anotações de validação, versionamento e documentação OpenAPI.
- `Map<String, Object>` deve ser usado somente nos trechos deliberadamente dinâmicos
  (`inputData`, `contextData`, `result` e configurações extensíveis).
- Campos necessários para o engine não devem ficar escondidos em um mapa genérico.
- Entidades de domínio não devem depender de `@RequestBody`, Jackson ou anotações JPA.
- O mesmo modelo não deve ser reutilizado automaticamente para request, response,
  persistência e domínio.
- O adapter REST deve traduzir `CreateFlowRequest` para um command da aplicação,
  por exemplo `CreateFlowCommand`.

Exemplo de separação:

```text
CreateFlowRequest
    -> CreateFlowCommand
        -> FlowDefinition
            -> FlowDefinitionEntity
```

Essa separação torna o contrato compreensível para outro agente e reduz o risco de
ele acoplar o domínio ao framework web ou ao banco de dados.

### 10.7 Respostas variáveis de provedores externos

Um provedor pode retornar estruturas diferentes para a mesma operação. Por
exemplo, `consult_person` pode retornar dados de menor de idade, dados de adulto
ou uma resposta de negócio indicando que a pessoa não foi encontrada. Essas
variantes pertencem ao contrato do provedor e devem ser tratadas no adapter da
integração.

O fluxo recomendado é:

```text
Provedor externo
    -> Provider Client recebe o payload
        -> Adapter identifica a variante
            -> DTO específico do provedor
                -> Resultado canônico do engine
```

#### DTOs das variantes do provedor

Cada resposta relevante deve ser representada por um DTO específico do contrato
externo, sem reutilizar diretamente o DTO de domínio:

```java
public record MinorPersonProviderResponse(
        String personId,
        String legalRepresentativeId
) {}

public record AdultPersonProviderResponse(
        String personId,
        Integer score,
        BigDecimal creditLimit
) {}

public record PersonNotFoundProviderResponse(
        String providerCode,
        String providerMessage
) {}
```

Quando o provedor disponibilizar um discriminador explícito, como `personType`,
`found` ou `resultCode`, ele deve ser usado para escolher a variante. Quando não
houver discriminador confiável, o adapter pode usar um desserializador customizado
ou ler inicialmente como `JsonNode` para classificar o payload por um conjunto de
campos documentado e testado.

#### Resultado canônico

Depois de desserializar a resposta externa, o adapter deve convertê-la para um
contrato estável que o engine consiga consumir:

```java
public record ConsultPersonResult(
        ConsultPersonOutcome outcome,
        PersonType personType,
        JsonNode data,
        ProviderEvidence evidence
) {}

public enum ConsultPersonOutcome {
    FOUND,
    NOT_FOUND
}

public enum PersonType {
    MINOR,
    ADULT
}
```

O resultado canônico deve expor somente os conceitos relevantes para o motor:

```json
{
  "outcome": "FOUND",
  "personType": "MINOR",
  "data": {
    "personId": "123",
    "legalRepresentativeId": "456"
  }
}
```

```json
{
  "outcome": "NOT_FOUND",
  "personType": null,
  "data": {}
}
```

Com isso, as condições do fluxo usam campos estáveis:

```text
result.outcome == NOT_FOUND
result.personType == MINOR
result.personType == ADULT
```

Elas não devem depender de um campo opcional do JSON externo existir, como
`response.object[0].pessoa.documento.identificador.codigo`.

#### Tratamento de HTTP 200 sem correspondência

Se o provedor retornar `HTTP 200` com um campo indicando “não encontrado”, isso
deve ser tratado como resultado de negócio esperado:

```text
HTTP 200 + found=false -> ConsultPersonOutcome.NOT_FOUND
```

Não deve ser convertido automaticamente em erro técnico. Já timeout, resposta
malformada, indisponibilidade e erro de autenticação devem ser classificados como
falhas técnicas, com política própria de retry, timeout ou fallback.

#### Contrato do adapter

O engine deve depender de uma porta de aplicação, e não do DTO externo:

```java
public interface PersonQueryPort {
    ConsultPersonResult consultPerson(PersonQuery query);
}
```

O adapter do provedor implementa a porta:

```java
public final class ProviderPersonQueryAdapter
        implements PersonQueryPort {

    @Override
    public ConsultPersonResult consultPerson(PersonQuery query) {
        // Chama o provedor, identifica a variante,
        // converte o DTO externo e normaliza o resultado.
        throw new UnsupportedOperationException();
    }
}
```

O DTO do provedor acompanha o contrato externo; o DTO ou resultado canônico
acompanha o significado que a aplicação precisa. Uma alteração estrutural no
provedor deve ser corrigida no adapter e coberta por testes de contrato. Dados
adicionais não críticos podem ser preservados em `JsonNode`/JSONB, mas campos
essenciais para uma decisão antifraude não devem depender de mapeamento livre no
banco.

---

## 11. Estados de execução

```text
PENDING
  -> RUNNING
  -> WAITING_ASYNC
  -> WAITING_APPROVAL
  -> PAUSED
  -> COMPLETED
  -> FAILED
  -> TIMED_OUT
  -> CANCELLED
```

As transições devem ser controladas pelo domínio. Atualizações concorrentes precisam usar locking otimista ou mecanismo equivalente para evitar que duas mensagens avancem a mesma execução.

---

## 12. Escalabilidade e desempenho

### 12.1 Perfil de carga

Devem ser medidos separadamente:

- execuções leves em tempo real;
- execuções médias com chamadas externas;
- execuções pesadas assíncronas;
- quantidade média e máxima de nodes;
- percentual de paralelismo;
- percentual de retries;
- percentual de aprovações humanas;
- tamanho de input, contexto e resultado;
- retenção necessária.

### 12.2 Estratégia

1. Manter definições de fluxo em cache, com invalidação na ativação.
2. Manter o runtime stateless.
3. Separar o caminho de decisão do histórico analítico.
4. Usar filas para etapas assíncronas e eventos.
5. Aplicar backpressure, rate limit e circuit breaker nas integrações.
6. Particionar tabelas de execução/auditoria conforme benchmark.
7. Permitir escalar horizontalmente o executor.
8. Garantir idempotência em chamadas externas e mensagens.

Redis e broker não precisam ser dependências obrigatórias do primeiro protótipo; devem ser introduzidos quando houver requisito concreto de cache distribuído ou processamento assíncrono.

---

## 13. Segurança e governança

Requisitos mínimos:

- autenticação e autorização por operação, tenant e escopo;
- API keys ou tokens associados a um único tenant, sem confiar em `tenantId`
  informado livremente no corpo da requisição;
- separação de credenciais de execução e de gestão;
- possibilidade de restringir um consumidor a fluxos ou operações específicas;
- segregação entre administração de fluxo e execução;
- validação de payloads;
- sanitização de dados sensíveis;
- mascaramento de documentos e credenciais nos logs;
- criptografia em trânsito e em repouso conforme padrão corporativo;
- trilha imutável de ativação e alteração;
- correlação sem expor dados pessoais;
- controle de quem pode publicar uma versão;
- limites para profundidade e tamanho de sub-flows;
- nenhuma expressão arbitrária executada no processo.

O engine não deve registrar secrets, tokens, documentos completos ou dados pessoais desnecessários em logs técnicos.

---

## 14. Observabilidade

Métricas mínimas:

- taxa de execuções por fluxo e versão;
- latência p50/p95/p99;
- taxa de aprovação, reprovação, erro e timeout;
- falhas por validator;
- retries por integração;
- tamanho e profundidade dos fluxos;
- quantidade de execuções aguardando callback/aprovação;
- backlog e dead-letter;
- utilização do banco;
- cache hit/miss;
- divergência entre decisão e persistência.

Logs devem conter `correlationId`, `executionId`, `flowKey`, `flowVersion`, `nodeId` e status, sem dados sensíveis.

---

## 15. Critérios de aceitação do MVP

O MVP será considerado tecnicamente válido quando:

1. For possível criar um fluxo em `DRAFT`.
2. O grafo for validado antes da ativação.
3. Houver no máximo uma versão ativa por seletor.
4. Uma execução usar uma versão congelada.
5. O engine executar nodes sequenciais e condicionais.
6. Um validator puder ser adicionado via Strategy/Registry sem alterar o executor.
7. Inputs e resultados de estruturas diferentes forem persistidos em JSONB.
8. O histórico por node puder ser consultado.
9. Houver idempotência para início de execução.
10. Um sub-flow simples puder ser executado com input/output mapping.
11. Timeout, retry e erro forem observáveis.
12. O serviço expuser health/readiness.
13. Testes cobrirem validação de grafo, seleção de fluxo, transições, versionamento e execução.
14. Um teste de carga inicial medir a capacidade antes de qualquer compromisso de escala.
15. Dois tenants puderem cadastrar fluxos com a mesma `flowKey` sem colisão ou
    acesso cruzado.
16. Uma execução registrar o `tenantId`, os dados de contexto utilizados e a
    versão da definição de fluxo aplicada.
17. Um analista puder criar uma transição baseada em papel, canal, grupo ou
    atributo genérico, com resultado determinístico.
18. Grupos de documentos puderem conter itens obrigatórios, alternativos e
    condicionais.

---

## 16. Roadmap sugerido

### Fase 0 — Descoberta e contratos

- confirmar cenários prioritários;
- definir estados e decisão de negócio;
- definir política de dados sensíveis;
- criar OpenAPI inicial;
- definir métricas de carga e SLOs.

### Fase 1 — Núcleo síncrono

- catálogo de fluxos;
- versionamento e ativação;
- validador estrutural;
- DAG executor sequencial;
- conditions em Java puro;
- PostgreSQL/JSONB;
- APIs de fluxo e execução;
- auditoria básica.

### Fase 2 — Extensibilidade

- Validator Registry;
- catálogo de node types;
- retries e timeouts;
- idempotência;
- cache de definições;
- integração externa isolada.

### Fase 3 — Composição e espera

- sub-flows;
- input/output mapping;
- fork/join;
- callbacks e polling;
- retomada;
- cancelamento;
- dead-letter.

### Fase 4 — Decisão humana e escala

- human approval;
- particionamento;
- broker;
- réplicas de leitura;
- projeções de consulta;
- testes de carga e caos.

### Fase 5 — Evolução

- editor visual externo;
- DSL de condições mais rico;
- avaliação de Temporal.io;
- separação física dos módulos com maior necessidade de escala;
- analytics e integração com modelos antifraude.

---

## 17. Riscos e decisões em aberto

### Riscos

1. Tentar suportar qualquer tipo de grafo antes de estabilizar o modelo.
2. Permitir regras livres em texto e criar risco de execução indevida.
3. Persistir snapshots excessivos e tornar o custo de armazenamento dominante.
4. Colocar chamadas externas lentas no caminho síncrono.
5. Criar microserviços demais antes de conhecer os limites de escala.
6. Permitir sub-flows recursivos sem limite.
7. Confundir configuração de fluxo com implementação de validator.

### Decisões que precisam de confirmação

1. Qual é o primeiro caso de uso obrigatório do MVP: empréstimo, onboarding, cartão ou outro?
2. A resposta do endpoint principal precisa ser síncrona para fluxos leves?
3. Qual é o SLO de latência para fluxo leve e fluxo médio?
4. Qual volume de pico deve ser usado no primeiro teste de carga?
5. Qual retenção é necessária para execução, auditoria e dados pessoais?
6. Quais integrações externas serão simuladas no MVP?
7. Quais campos genéricos farão parte do contrato de contexto: papel, canal,
   grupos, região, produto ou outros atributos?
8. Qual será a retenção de documentos, evidências e dados pessoais?
9. A ativação de fluxo exige aprovação de dois níveis?
10. O broker corporativo já está definido?
11. Há um padrão corporativo obrigatório para autenticação, eventos e observabilidade?
12. O fluxo pode conter ciclos controlados ou deve ser estritamente DAG?
13. A versão do sub-flow deve ser resolvida no início do pai ou no momento da chamada?

---

## 18. Recomendação final

Construir primeiro um **Validation Flow Engine modular**, com:

- Java 17+ e Spring Boot;
- arquitetura hexagonal;
- isolamento multi-tenant desde o primeiro contrato;
- Execution API e Flow Management API separadas logicamente;
- frontend de gestão fora do MVP;
- PostgreSQL com JSONB;
- DAG Executor como abstração central;
- Strategy Registry para validadores;
- condições estruturadas avaliadas por Java puro;
- contexto genérico para condições de tipo, papel, canal e grupo;
- grupos de documentos declarados e versionados dentro do fluxo;
- versionamento imutável;
- histórico separado conceitualmente do caminho de decisão;
- sub-flows com limite e mapeamento explícito;
- poucos deployables, com fronteiras preparadas para extração futura.

Não iniciar com Drools, Neo4j, dezenas de microserviços ou um editor visual. A primeira entrega deve provar o domínio, os contratos e a capacidade de execução com um caso de uso real. Depois, as decisões de broker, cache, Temporal.io, particionamento e separação física devem ser guiadas por métricas de carga e requisitos operacionais confirmados.
