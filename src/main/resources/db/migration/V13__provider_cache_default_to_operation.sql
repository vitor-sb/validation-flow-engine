-- brasilapi-cnpj declares a single operation `cnpj`; rows stored under the legacy `default` move to it.
-- Entries: on conflict the most recent (fetched_at) wins. Policies have no timestamp: the row already under `cnpj` wins.
DELETE FROM provider_cache_entry c USING provider_cache_entry d
WHERE c.provider_key = 'brasilapi-cnpj' AND c.operation = 'cnpj'
  AND d.provider_key = c.provider_key AND d.operation = 'default'
  AND d.tenant_id = c.tenant_id AND d.request_key = c.request_key AND d.fetched_at > c.fetched_at;

DELETE FROM provider_cache_entry d USING provider_cache_entry c
WHERE d.provider_key = 'brasilapi-cnpj' AND d.operation = 'default'
  AND c.provider_key = d.provider_key AND c.operation = 'cnpj'
  AND c.tenant_id = d.tenant_id AND c.request_key = d.request_key;

UPDATE provider_cache_entry SET operation = 'cnpj' WHERE provider_key = 'brasilapi-cnpj' AND operation = 'default';

DELETE FROM provider_cache_policy d USING provider_cache_policy c
WHERE d.provider_key = 'brasilapi-cnpj' AND d.operation = 'default'
  AND c.provider_key = d.provider_key AND c.operation = 'cnpj' AND c.tenant_id = d.tenant_id;

UPDATE provider_cache_policy SET operation = 'cnpj' WHERE provider_key = 'brasilapi-cnpj' AND operation = 'default';
