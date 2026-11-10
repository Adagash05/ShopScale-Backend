-- V12: business counters, product kinds, recipes, restaurant (modifiers, tables, kitchen orders), wastage.

CREATE TABLE business_counters (
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(40) NOT NULL,
  value       bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (business_id, name)
);

ALTER TABLE products ADD COLUMN kind varchar(12) NOT NULL DEFAULT 'GOODS' CHECK (kind IN ('GOODS','INGREDIENT','MENU','FINISHED'));
ALTER TABLE sales ADD COLUMN surcharge numeric(14,2) NOT NULL DEFAULT 0;

-- A recipe says how one variant is made from ingredient variants.
--   ON_SALE    : made to order (menu items). Ingredients are used when the item is sold.
--   PRODUCTION : made in batches (production orders, catering preparation).
CREATE TABLE recipes (
  id             uuid PRIMARY KEY,
  business_id    uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id     uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  yield_quantity numeric(14,3) NOT NULL DEFAULT 1 CHECK (yield_quantity > 0),
  mode           varchar(12) NOT NULL DEFAULT 'ON_SALE' CHECK (mode IN ('ON_SALE','PRODUCTION')),
  notes          varchar(500),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (variant_id)
);
CREATE TABLE recipe_lines (
  id                    uuid PRIMARY KEY,
  recipe_id             uuid NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  ingredient_variant_id uuid NOT NULL REFERENCES product_variants(id),
  quantity              numeric(14,3) NOT NULL CHECK (quantity > 0)
);
CREATE INDEX idx_recipe_lines_recipe ON recipe_lines(recipe_id);

-- Modifiers (extras, sizes, "no onions"...) attached to menu items.
CREATE TABLE modifier_groups (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(80) NOT NULL,
  required    boolean NOT NULL DEFAULT false,
  max_select  integer NOT NULL DEFAULT 1 CHECK (max_select >= 1),
  active      boolean NOT NULL DEFAULT true,
  UNIQUE (business_id, name)
);
CREATE TABLE modifiers (
  id                    uuid PRIMARY KEY,
  group_id              uuid NOT NULL REFERENCES modifier_groups(id) ON DELETE CASCADE,
  business_id           uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name                  varchar(80) NOT NULL,
  price_delta           numeric(14,2) NOT NULL DEFAULT 0 CHECK (price_delta >= 0),
  ingredient_variant_id uuid REFERENCES product_variants(id),
  ingredient_quantity   numeric(14,3),
  active                boolean NOT NULL DEFAULT true
);
CREATE INDEX idx_modifiers_group ON modifiers(group_id);
CREATE TABLE product_modifier_groups (
  product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  group_id   uuid NOT NULL REFERENCES modifier_groups(id) ON DELETE CASCADE,
  PRIMARY KEY (product_id, group_id)
);

CREATE TABLE restaurant_tables (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id   uuid REFERENCES branches(id),
  name        varchar(40) NOT NULL,
  seats       integer NOT NULL DEFAULT 2 CHECK (seats > 0),
  active      boolean NOT NULL DEFAULT true,
  UNIQUE (business_id, name)
);

CREATE TABLE kitchen_orders (
  id            uuid PRIMARY KEY,
  business_id   uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id     uuid NOT NULL REFERENCES branches(id),
  number        bigint NOT NULL,
  table_id      uuid REFERENCES restaurant_tables(id),
  order_type    varchar(10) NOT NULL CHECK (order_type IN ('DINE_IN','TAKEAWAY','DELIVERY')),
  customer_name varchar(150),
  note          varchar(500),
  status        varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAID','CANCELLED')),
  sale_id       uuid REFERENCES sales(id),
  created_by    uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, number)
);
CREATE INDEX idx_kitchen_orders_open ON kitchen_orders(business_id, status, created_at);
CREATE UNIQUE INDEX uq_kitchen_open_table ON kitchen_orders(table_id) WHERE status = 'OPEN' AND table_id IS NOT NULL;
CREATE TABLE kitchen_order_items (
  id              uuid PRIMARY KEY,
  order_id        uuid NOT NULL REFERENCES kitchen_orders(id) ON DELETE CASCADE,
  business_id     uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id      uuid NOT NULL REFERENCES product_variants(id),
  item_name       varchar(250) NOT NULL,
  quantity        numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_price      numeric(14,2) NOT NULL,
  modifiers_total numeric(14,2) NOT NULL DEFAULT 0,   -- per unit
  note            varchar(300),
  status          varchar(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','PREPARING','READY','SERVED','CANCELLED')),
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_kitchen_items_order ON kitchen_order_items(order_id);
CREATE TABLE kitchen_item_modifiers (
  id          uuid PRIMARY KEY,
  item_id     uuid NOT NULL REFERENCES kitchen_order_items(id) ON DELETE CASCADE,
  modifier_id uuid NOT NULL REFERENCES modifiers(id),
  name        varchar(80) NOT NULL,
  price_delta numeric(14,2) NOT NULL
);

CREATE TABLE wastage_records (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id   uuid NOT NULL REFERENCES branches(id),
  variant_id  uuid NOT NULL REFERENCES product_variants(id),
  item_name   varchar(250) NOT NULL,
  quantity    numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_cost   numeric(14,2) NOT NULL,
  reason      varchar(12) NOT NULL CHECK (reason IN ('SPOILAGE','EXPIRED','DAMAGE','PREP_WASTE','THEFT','OTHER')),
  note        varchar(300),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_wastage_business_time ON wastage_records(business_id, created_at DESC);

INSERT INTO plan_features (plan_code, feature)
SELECT code, f FROM plans CROSS JOIN unnest(ARRAY['RESTAURANT','RECIPES','WASTAGE','PRODUCTION','CATERING']) AS f WHERE code IN ('ESSENTIAL','GROWTH','BUSINESS') ON CONFLICT DO NOTHING;
