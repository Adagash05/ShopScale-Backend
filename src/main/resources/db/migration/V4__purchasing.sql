-- Suppliers, purchases (receiving stock) and what the business owes suppliers.
ALTER TABLE businesses ADD COLUMN purchase_counter bigint NOT NULL DEFAULT 0;

CREATE TABLE suppliers (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(150) NOT NULL,
  phone       varchar(40),
  email       varchar(255),
  address     text,
  notes       text,
  balance     numeric(14,2) NOT NULL DEFAULT 0,   -- money the business owes this supplier
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppliers_business ON suppliers(business_id);

CREATE TABLE purchases (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  purchase_number   bigint NOT NULL,
  supplier_id       uuid REFERENCES suppliers(id),
  reference         varchar(60),
  total             numeric(14,2) NOT NULL,
  amount_paid       numeric(14,2) NOT NULL,
  amount_owed       numeric(14,2) NOT NULL,
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, purchase_number)
);
CREATE INDEX idx_purchases_business_time ON purchases(business_id, created_at DESC);
CREATE INDEX idx_purchases_supplier ON purchases(supplier_id);
CREATE UNIQUE INDEX uq_purchases_client_request ON purchases(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE purchase_items (
  id            uuid PRIMARY KEY,
  purchase_id   uuid NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
  business_id   uuid NOT NULL,
  variant_id    uuid NOT NULL REFERENCES product_variants(id),
  product_name  varchar(200) NOT NULL,
  variant_label varchar(120),
  quantity      integer NOT NULL CHECK (quantity > 0),
  unit_cost     numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
  line_total    numeric(14,2) NOT NULL
);
CREATE INDEX idx_purchase_items_purchase ON purchase_items(purchase_id);

CREATE TABLE supplier_payments (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  supplier_id uuid NOT NULL REFERENCES suppliers(id),
  amount      numeric(14,2) NOT NULL CHECK (amount > 0),
  method      varchar(20) NOT NULL,
  note        varchar(300),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_supplier_payments_supplier ON supplier_payments(supplier_id);
