-- V17: customer invoices (standalone, from a sale, or from an order), their payments, and per-business invoice settings.
CREATE TABLE invoices (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id          uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  invoice_number       bigint NOT NULL,
  customer_id          uuid REFERENCES customers(id),
  customer_name        varchar(150) NOT NULL,
  customer_phone       varchar(40),
  customer_email       varchar(255),
  customer_address     varchar(400),
  status               varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','PARTIALLY_PAID','PAID','CANCELLED')),
  issue_date           date NOT NULL,
  due_date             date,
  payment_terms        varchar(200),
  subtotal             numeric(14,2) NOT NULL CHECK (subtotal >= 0),
  line_discounts       numeric(14,2) NOT NULL DEFAULT 0,
  tax_total            numeric(14,2) NOT NULL DEFAULT 0,
  invoice_discount     numeric(14,2) NOT NULL DEFAULT 0,
  total                numeric(14,2) NOT NULL CHECK (total >= 0),
  amount_paid          numeric(14,2) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0 AND amount_paid <= total),
  note                 varchar(1000),
  payment_instructions varchar(1000),
  source_sale_id       uuid REFERENCES sales(id),
  source_document_id   uuid REFERENCES sales_documents(id),
  share_token          varchar(64) NOT NULL UNIQUE,
  created_by           uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  sent_at              timestamptz,
  cancelled_at         timestamptz,
  UNIQUE (business_id, invoice_number)
);
CREATE INDEX idx_invoices_business_created ON invoices(business_id, created_at DESC);
CREATE INDEX idx_invoices_customer ON invoices(business_id, customer_id);
-- One live invoice per sale / order.
CREATE UNIQUE INDEX uq_invoice_sale ON invoices(source_sale_id) WHERE source_sale_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX uq_invoice_document ON invoices(source_document_id) WHERE source_document_id IS NOT NULL AND status <> 'CANCELLED';

CREATE TABLE invoice_items (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_id  uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  business_id uuid NOT NULL,
  position    int NOT NULL,
  description varchar(300) NOT NULL,
  quantity    numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_price  numeric(14,2) NOT NULL CHECK (unit_price >= 0),
  discount    numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
  tax_rate    numeric(5,2) NOT NULL DEFAULT 0 CHECK (tax_rate BETWEEN 0 AND 100),
  line_total  numeric(14,2) NOT NULL,
  tax_amount  numeric(14,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_invoice_items_invoice ON invoice_items(invoice_id);

CREATE TABLE invoice_payments (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_id        uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  business_id       uuid NOT NULL,
  amount            numeric(14,2) NOT NULL CHECK (amount > 0),
  method            varchar(20) NOT NULL,
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now()
);
-- A retried request (double tap, flaky network) cannot record the same payment twice.
CREATE UNIQUE INDEX uq_invoice_payment_request ON invoice_payments(invoice_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE invoice_settings (
  business_id          uuid PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
  payment_instructions varchar(1000),
  default_terms        varchar(200),
  default_tax_rate     numeric(5,2) NOT NULL DEFAULT 0,
  logo                 bytea,
  logo_type            varchar(60)
);
