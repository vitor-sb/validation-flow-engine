# PRD: Pendências conhecidas (RF-07, RF-13 e itens adiados)

> Derivado de `coverage.md` (seção "Lacunas conhecidas") e de `00-v0.1-spec.md`. Os PRDs 01 a 08 estão concluídos (todas as stories em `scripts/ralph/archive/` e no `prd.json` ativo com `passes: true`).
> Os checkboxes dos PRDs 01 a 08 nunca foram marcados; o progresso real está no `progress.txt`, no `archive/` e no `coverage.md`.
> Ordem sugerida: A -> B -> C. Cada story roda `mvn -B verify` (~8 min, em background).

## Estado atual

- MVP (critérios 1-18 do v0.1): OK.
- Provedores: config por tenant, pausa/retomada, async (callback + polling + dead-letter), cache por operação, operações declaradas pelo provedor.
- Único provedor real: `brasilapi` (síncrono, `cnpj` e `cep`).

## Feature A — Robustez do fluxo assíncrono

#### US-001: Fallback para dado vencido quando o polling falha
**Description:** Como operador, quero que uma falha de polling use o dado vencido do cache quando a política permitir, como já ocorre na chamada síncrona.

**Acceptance Criteria:**
- [ ] Falha definitiva de polling consulta `provider_cache_entry` vencida e, se a política permite `staleOnError`, conclui com o dado vencido
- [ ] O resultado registra que o dado é vencido (mesmo marcador usado no caminho síncrono)
- [ ] Sem permissão da política, o comportamento atual (`DEAD`) não muda
- [ ] Teste com provedor falso: polling falha + entrada vencida + política permitindo -> COMPLETED com marcador; sem permissão -> DEAD
- [ ] `mvn -B verify` passa

#### US-002: Callback tardio após o sweep de dead-letter
**Description:** Como integrador, quero que um callback legítimo que chega depois de a chamada virar `DEAD` não receba 409 sem explicação.

**Acceptance Criteria:**
- [ ] Decisão registrada no `coverage.md`: aceitar e reabrir a chamada `DEAD`, ou responder com código/corpo explícito (ex.: 410 `CALL_DEAD`)
- [ ] Implementado conforme a decisão, auditado e com métrica `validation.provider.callback_late`
- [ ] Teste: callback após sweep -> comportamento decidido; callback duplicado em chamada `COMPLETED` segue idempotente
- [ ] `openapi.yaml` atualizado
- [ ] `mvn -B verify` passa

## Feature B — Adapter assíncrono real

#### US-003: Primeiro adapter assíncrono (Unico ou BigDataCorp)
**Description:** Como desenvolvedor, quero um adapter real com callback e/ou polling, para validar o contrato `DataProvider.start/poll/normalizeCallback` fora de provedores falsos.

**Acceptance Criteria:**
- [ ] Provedor escolhido e registrado no `coverage.md` (credenciais por tenant via `provider_config`)
- [ ] Adapter declara `operations()` e implementa `start`, `poll` e/ou `normalizeCallback` com `cacheKeyParams`
- [ ] Teste contra servidor HTTP simulado (padrão do `BrasilApiProviderTest`) cobrindo sucesso, erro, timeout e `found:false`
- [ ] Teste de ponta a ponta: fluxo com `EXTERNAL_CALL` pausa em `WAITING`, recebe callback (ou poll) e retoma
- [ ] README: linha na tabela de provedores
- [ ] `mvn -B verify` passa

## Feature C — RF-13 ainda não coberto

> Cada item abaixo é opcional e independente. Só vira story quando houver necessidade real; ordem por valor esperado.

#### US-004: `sensitivity` por política de cache
**Acceptance Criteria:**
- [ ] Política ganha `sensitivity`; dados sensíveis têm retenção/TTL máximo próprios
- [ ] Criptografia em repouso da entrada quando a política exigir (decisão de chave/KMS registrada)
- [ ] Testes de política, leitura e limpeza por `valid_until`
- [ ] `mvn -B verify` passa

#### US-005: Política de cache versionada e aprovação de `CUSTOM`
**Acceptance Criteria:**
- [ ] Alteração de política cria nova versão; execuções registram a versão usada
- [ ] Política `CUSTOM` exige aprovação (reutiliza o padrão de aprovação de ativação)
- [ ] Testes de versão e de aprovação
- [ ] `mvn -B verify` passa

#### US-006: Invalidação por evento e TTL por produto/canal/tipo de usuário
**Acceptance Criteria:**
- [ ] Endpoint/porta para invalidar entradas por `(tenant, provedor, operação, chave)`
- [ ] TTL pode variar por produto/canal/tipo de usuário, resolvido a partir do contexto da execução
- [ ] Testes de invalidação e de resolução de TTL
- [ ] `mvn -B verify` passa

## Itens adiados por decisão (não entram agora)

fork/join, ASYNC_VALIDATION, particionamento, broker, réplicas de leitura, projeções, aprovação em dois níveis para ativação, camada Redis de cache.

## Operação (fora de código de negócio)

- Ao fechar um PRD, atualizar `coverage.md` e marcar os checkboxes do PRD.
- Teste de carga: rodar fora de `/workspace` (ver `loadtest/REPORT.md`).
