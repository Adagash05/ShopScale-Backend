-- V28: a repeated "create invoice" request (double tap, retry on a slow network) must not create a second invoice.
-- Adds an optional request id to invoices. Existing invoices keep NULL and are untouched; nothing is removed or rewritten.
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS client_request_id varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_client_request ON invoices (business_id, client_request_id) WHERE client_request_id IS NOT NULL;
