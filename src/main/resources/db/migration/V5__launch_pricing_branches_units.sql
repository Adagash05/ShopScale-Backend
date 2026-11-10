-- V5: launch pricing, branches, branch-level stock, decimal quantities, units and unit conversions.
-- Additive / non-destructive. Existing subscriptions, payments and data keep their meaning.

-- 1. Launch pricing. ESSENTIAL is retired for NEW signups (active=false) but its row, price, limits and features are
-- kept so existing ESSENTIAL subscribers keep exactly what they have. No subscription row is touched.
UPDATE plans SET active = false, sort_order = 0 WHERE code = 'ESSENTIAL';
INSERT INTO plans (code, name, monthly_price, annual_price, max_users, max_locations, max_items, sort_order, active)
VALUES ('STARTER', 'Starter', 4900.00, 49000.00, 1, 1, NULL, 1, true) ON CONFLICT (code) DO NOTHING;
UPDATE plans SET monthly_price = 9900.00,  annual_price = 99000.00,  max_users = 5,  max_locations = 2, max_items = NULL, sort_order = 2 WHERE code = 'GROWTH';
UPDATE plans SET monthly_price = 19000.00, annual_price = 190000.00, max_users = 10, max_locations = 5, max_items = NULL, sort_order = 3 WHERE code = 'BUSINESS';

INSERT INTO plan_features (plan_code, feature)
SELECT 'STARTER', f FROM unnest(ARRAY['POS','BASIC_INVENTORY','EXPENSES','BASIC_DASHBOARD','CUSTOMER_CREDIT',
  'STOCK_HISTORY','DATA_EXPORT','PURCHASING','CASH_ACCOUNTS']) AS f ON CONFLICT DO NOTHING;
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['PURCHASING','CASH_ACCOUNTS']) AS f
WHERE p.code IN ('ESSENTIAL','GROWTH','BUSINESS') ON CONFLICT DO NOTHING;
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['BRANCH_TRANSFERS','CASH_RECONCILIATION']) AS f
WHERE p.code IN ('GROWTH','BUSINESS') ON CONFLICT DO NOTHING;
INSERT INTO plan_features (plan_code, feature)
SELECT 'BUSINESS', f FROM unnest(ARRAY['CONSOLIDATED_REPORTING','ADVANCED_INVENTORY']) AS f ON CONFLICT DO NOTHING;

-- 2. Branches
CREATE TABLE branches (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(100) NOT NULL,
  address     varchar(500),
  phone       varchar(40),
  is_main     boolean NOT NULL DEFAULT false,
  active      boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_branch_name ON branches(business_id, lower(name));
CREATE UNIQUE INDEX uq_branch_main ON branches(business_id) WHERE is_main;
CREATE INDEX idx_branches_business ON branches(business_id);
INSERT INTO branches (id, business_id, name, address, phone, is_main)
SELECT gen_random_uuid(), b.id, 'Main branch', b.address, b.phone, true FROM businesses b;

-- 3. Decimal quantities (numeric(14,3)) everywhere stock is counted.
ALTER TABLE product_variants ALTER COLUMN quantity TYPE numeric(14,3), ALTER COLUMN low_stock_threshold TYPE numeric(14,3);
ALTER TABLE stock_movements  ALTER COLUMN quantity_change TYPE numeric(14,3), ALTER COLUMN balance_after TYPE numeric(14,3);
ALTER TABLE sale_items       DROP CONSTRAINT IF EXISTS sale_items_quantity_check;
ALTER TABLE sale_items       ALTER COLUMN quantity TYPE numeric(14,3);
ALTER TABLE sale_items       ADD CONSTRAINT sale_items_quantity_check CHECK (quantity > 0);
ALTER TABLE purchase_items   DROP CONSTRAINT IF EXISTS purchase_items_quantity_check;
ALTER TABLE purchase_items   ALTER COLUMN quantity TYPE numeric(14,3);
ALTER TABLE purchase_items   ADD CONSTRAINT purchase_items_quantity_check CHECK (quantity > 0);

-- 4. Units and conversions. A variant has a base unit; sales/purchases may use a convertible unit.
ALTER TABLE product_variants ADD COLUMN unit varchar(20) NOT NULL DEFAULT 'piece';
ALTER TABLE product_variants ADD COLUMN allow_decimal boolean NOT NULL DEFAULT false;
ALTER TABLE sale_items ADD COLUMN unit varchar(20);
ALTER TABLE purchase_items ADD COLUMN unit varchar(20);
CREATE TABLE unit_conversions (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id  uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  unit        varchar(20) NOT NULL,
  factor      numeric(14,6) NOT NULL CHECK (factor > 0),   -- how many base units in one of this unit
  UNIQUE (variant_id, unit)
);
CREATE INDEX idx_unit_conv_business ON unit_conversions(business_id);

-- 5. Branch stock. Invariant: sum(branch_stock.quantity) = product_variants.quantity.
CREATE TABLE branch_stock (
  branch_id  uuid NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
  variant_id uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  quantity   numeric(14,3) NOT NULL DEFAULT 0,
  reserved   numeric(14,3) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (branch_id, variant_id)
);
CREATE INDEX idx_branch_stock_variant ON branch_stock(variant_id);
INSERT INTO branch_stock (branch_id, variant_id, business_id, quantity)
SELECT br.id, v.id, v.business_id, v.quantity FROM product_variants v JOIN branches br ON br.business_id = v.business_id AND br.is_main;

ALTER TABLE stock_movements ADD COLUMN branch_id uuid REFERENCES branches(id);
UPDATE stock_movements m SET branch_id = br.id FROM branches br WHERE br.business_id = m.business_id AND br.is_main;
ALTER TABLE sales ADD COLUMN branch_id uuid REFERENCES branches(id);
UPDATE sales s SET branch_id = br.id FROM branches br WHERE br.business_id = s.business_id AND br.is_main;
ALTER TABLE purchases ADD COLUMN branch_id uuid REFERENCES branches(id);
UPDATE purchases p SET branch_id = br.id FROM branches br WHERE br.business_id = p.business_id AND br.is_main;
CREATE INDEX idx_sales_branch ON sales(branch_id);

-- 6. Stock transfers between branches
ALTER TABLE businesses ADD COLUMN transfer_counter bigint NOT NULL DEFAULT 0;
CREATE TABLE stock_transfers (
  id               uuid PRIMARY KEY,
  business_id      uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  transfer_number  bigint NOT NULL,
  from_branch_id   uuid NOT NULL REFERENCES branches(id),
  to_branch_id     uuid NOT NULL REFERENCES branches(id),
  status           varchar(20) NOT NULL,   -- IN_TRANSIT, RECEIVED, CANCELLED
  note             varchar(300),
  client_request_id varchar(64),
  created_by       uuid,
  received_by      uuid,
  created_at       timestamptz NOT NULL DEFAULT now(),
  received_at      timestamptz,
  UNIQUE (business_id, transfer_number),
  CHECK (from_branch_id <> to_branch_id)
);
CREATE UNIQUE INDEX uq_transfer_request ON stock_transfers(business_id, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE TABLE stock_transfer_items (
  id          uuid PRIMARY KEY,
  transfer_id uuid NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
  business_id uuid NOT NULL,
  variant_id  uuid NOT NULL REFERENCES product_variants(id),
  quantity    numeric(14,3) NOT NULL CHECK (quantity > 0),
  quantity_received numeric(14,3)
);
CREATE INDEX idx_transfer_items ON stock_transfer_items(transfer_id);
