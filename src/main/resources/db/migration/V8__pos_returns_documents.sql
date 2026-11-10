-- V8: POS - partial refunds, voids, sales channel, quotations and sales orders.
ALTER TABLE sales ADD COLUMN channel varchar(15) NOT NULL DEFAULT 'POS';       -- POS, ORDER, ONLINE, RESTAURANT, CATERING
ALTER TABLE sales ADD COLUMN refunded_amount numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN refunded_cost numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN void_reason varchar(300);
ALTER TABLE sales ADD COLUMN voided_by uuid;
-- Sales that were fully refunded before this migration count as fully refunded.
UPDATE sales SET refunded_amount = total, refunded_cost = cost_total WHERE status = 'REFUNDED';
ALTER TABLE sale_items ADD COLUMN quantity_refunded numeric(14,3) NOT NULL DEFAULT 0;
UPDATE sale_items i SET quantity_refunded = i.quantity FROM sales s WHERE s.id = i.sale_id AND s.status = 'REFUNDED';
CREATE INDEX idx_sales_channel ON sales(business_id, channel, created_at);

CREATE TABLE sale_refunds (
  id            uuid PRIMARY KEY,
  business_id   uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  sale_id       uuid NOT NULL REFERENCES sales(id),
  amount        numeric(14,2) NOT NULL,        -- value of the goods returned
  cash_refunded numeric(14,2) NOT NULL,        -- money actually paid back
  owed_reduced  numeric(14,2) NOT NULL,        -- unpaid balance cancelled instead of paying out
  method        varchar(20),
  reason        varchar(300),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sale_refunds_sale ON sale_refunds(sale_id);
CREATE TABLE sale_refund_items (
  id           uuid PRIMARY KEY,
  refund_id    uuid NOT NULL REFERENCES sale_refunds(id) ON DELETE CASCADE,
  sale_item_id uuid NOT NULL REFERENCES sale_items(id),
  quantity     numeric(14,3) NOT NULL CHECK (quantity > 0),
  amount       numeric(14,2) NOT NULL,
  returned_to_stock boolean NOT NULL DEFAULT true
);

-- Quotations and sales orders share one table (kind QUOTE or ORDER). Prices are fixed by the server when created.
ALTER TABLE businesses ADD COLUMN document_counter bigint NOT NULL DEFAULT 0;
CREATE TABLE sales_documents (
  id             uuid PRIMARY KEY,
  business_id    uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id      uuid REFERENCES branches(id),
  kind           varchar(10) NOT NULL,
  doc_number     bigint NOT NULL,
  customer_id    uuid REFERENCES customers(id),
  status         varchar(15) NOT NULL,          -- OPEN, ACCEPTED, REJECTED, EXPIRED, CONVERTED, CANCELLED
  valid_until    date,
  delivery_date  date,
  subtotal       numeric(14,2) NOT NULL,
  discount       numeric(14,2) NOT NULL DEFAULT 0,
  total          numeric(14,2) NOT NULL,
  deposit_amount numeric(14,2) NOT NULL DEFAULT 0,
  deposit_method varchar(20),
  note           varchar(500),
  sale_id        uuid REFERENCES sales(id),
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, kind, doc_number)
);
CREATE INDEX idx_sales_docs_business ON sales_documents(business_id, kind, created_at DESC);
CREATE TABLE sales_document_items (
  id          uuid PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES sales_documents(id) ON DELETE CASCADE,
  business_id uuid NOT NULL,
  variant_id  uuid NOT NULL REFERENCES product_variants(id),
  product_name varchar(200) NOT NULL,
  variant_label varchar(120),
  quantity    numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_price  numeric(14,2) NOT NULL,
  line_total  numeric(14,2) NOT NULL
);
CREATE INDEX idx_sales_doc_items ON sales_document_items(document_id);
