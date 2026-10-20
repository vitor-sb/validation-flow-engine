-- `brasilapi-cnpj` becomes `brasilapi` (operation `cnpj`), with no alias. Idempotent; where a `brasilapi` row already exists it wins.
DELETE FROM provider_config o USING provider_config n
WHERE o.provider_key = 'brasilapi-cnpj' AND n.provider_key = 'brasilapi' AND n.tenant_id = o.tenant_id;
UPDATE provider_config SET provider_key = 'brasilapi' WHERE provider_key = 'brasilapi-cnpj';

UPDATE provider_config_audit SET provider_key = 'brasilapi' WHERE provider_key = 'brasilapi-cnpj';

DELETE FROM provider_cache_policy o USING provider_cache_policy n
WHERE o.provider_key = 'brasilapi-cnpj' AND n.provider_key = 'brasilapi' AND n.tenant_id = o.tenant_id AND n.operation = o.operation;
UPDATE provider_cache_policy SET provider_key = 'brasilapi' WHERE provider_key = 'brasilapi-cnpj';

DELETE FROM provider_cache_entry o USING provider_cache_entry n
WHERE o.provider_key = 'brasilapi-cnpj' AND n.provider_key = 'brasilapi' AND n.tenant_id = o.tenant_id
  AND n.operation = o.operation AND n.request_key = o.request_key;
UPDATE provider_cache_entry SET provider_key = 'brasilapi' WHERE provider_key = 'brasilapi-cnpj';

UPDATE provider_call SET provider_key = 'brasilapi' WHERE provider_key = 'brasilapi-cnpj';

-- Nodes using the old validator (or EXTERNAL_CALL provider) now name `brasilapi` plus `operation: cnpj`.
CREATE OR REPLACE FUNCTION pg_temp.rename_brasilapi(graph jsonb) RETURNS jsonb LANGUAGE sql AS $$
    SELECT jsonb_set(graph, '{nodes}', (
        SELECT jsonb_object_agg(e.k, CASE
            WHEN e.v #>> '{config,validatorType}' = 'brasilapi-cnpj'
                THEN jsonb_set(jsonb_set(e.v, '{config,validatorType}', '"brasilapi"'), '{config,operation}', '"cnpj"')
            WHEN e.v #>> '{config,providerKey}' = 'brasilapi-cnpj'
                THEN jsonb_set(jsonb_set(e.v, '{config,providerKey}', '"brasilapi"'), '{config,operation}', '"cnpj"')
            ELSE e.v END)
        FROM jsonb_each(graph -> 'nodes') e(k, v)))
$$;

UPDATE flow_definition SET graph_definition = pg_temp.rename_brasilapi(graph_definition)
WHERE jsonb_typeof(graph_definition -> 'nodes') = 'object' AND graph_definition::text LIKE '%brasilapi-cnpj%';

UPDATE flow_execution SET flow_snapshot = pg_temp.rename_brasilapi(flow_snapshot)
WHERE status IN ('PENDING', 'RUNNING', 'WAITING')
  AND jsonb_typeof(flow_snapshot -> 'nodes') = 'object' AND flow_snapshot::text LIKE '%brasilapi-cnpj%';
