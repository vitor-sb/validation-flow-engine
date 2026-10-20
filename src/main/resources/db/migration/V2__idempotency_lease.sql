ALTER TABLE idempotency_key ADD COLUMN locked_until TIMESTAMPTZ NOT NULL DEFAULT now();
