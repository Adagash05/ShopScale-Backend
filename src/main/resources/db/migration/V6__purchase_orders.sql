-- V6: purchase orders with partial goods receiving. Each receiving creates an ordinary purchase (stock, cost, supplier balance)
-- linked to the order, so purchase history and supplier liabilities stay consistent.
ALTER TABLE businesses ADD COLUMN po_counter bigint NOT NULL DEFAULT 0;

CREATE TABLE purchase_orders (
  id            uuid PRIMARY KEY,
  business_id   uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id     uuid REFERENCES branches(id),
  po_number     bigint NOT NULL,
  supplier_id   uuid NOT NULL REFERENCES suppliers(id),
  status        varchar(20) NOT NULL,        -- ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED
  expected_date date,
  note          varchar(500),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  closed_at     timestamptz,
  UNIQUE (business_id, po_number)
);
CREATE INDEX idx_po_business_time ON purchase_orders(business_id, created_at DESC);
CREATE INDEX idx_po_supplier ON purchase_orders(supplier_id);

CREATE TABLE purchase_order_items (
  id                uuid PRIMARY KEY,
  purchase_order_id uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
  business_id       uuid NOT NULL,
  variant_id        uuid NOT NULL REFERENCES product_variants(id),
  quantity_ordered  numeric(14,3) NOT NULL CHECK (quantity_ordered > 0),
  quantity_received numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
  unit_cost         numeric(14,2) NOT NULL CHECK (unit_cost >= 0)
);
CREATE INDEX idx_po_items_po ON purchase_order_items(purchase_order_id);

ALTER TABLE purchases ADD COLUMN purchase_order_id uuid REFERENCES purchase_orders(id);
CREATE INDEX idx_purchases_po ON purchases(purchase_order_id);
