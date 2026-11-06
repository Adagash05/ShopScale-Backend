-- ShopScale core schema (milestone 1). Every tenant table carries business_id.

CREATE TABLE users (
  id            uuid PRIMARY KEY,
  email         varchar(255) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  full_name     varchar(150) NOT NULL,
  phone         varchar(40),
  active        boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id         uuid PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash varchar(64) NOT NULL UNIQUE,
  family_id  uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);

CREATE TABLE businesses (
  id                   uuid PRIMARY KEY,
  name                 varchar(150) NOT NULL,
  slug                 varchar(40) NOT NULL UNIQUE,
  business_type        varchar(30) NOT NULL,
  subtype              varchar(60),
  description          text,
  phone                varchar(40),
  email                varchar(255),
  address              text,
  currency             varchar(3) NOT NULL DEFAULT 'NGN',
  timezone             varchar(60) NOT NULL DEFAULT 'Africa/Lagos',
  allow_negative_stock boolean NOT NULL DEFAULT false,
  sale_counter         bigint NOT NULL DEFAULT 0,
  status               varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  role        varchar(20) NOT NULL,
  active      boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, business_id)
);
CREATE INDEX idx_memberships_business ON memberships(business_id);

CREATE TABLE plans (
  code          varchar(30) PRIMARY KEY,
  name          varchar(60) NOT NULL,
  monthly_price numeric(14,2) NOT NULL,
  annual_price  numeric(14,2) NOT NULL,
  max_users     integer NOT NULL,
  max_locations integer NOT NULL,
  max_items     integer,           -- NULL = unlimited (fair use)
  active        boolean NOT NULL DEFAULT true,
  sort_order    integer NOT NULL DEFAULT 0
);

CREATE TABLE plan_features (
  plan_code varchar(30) NOT NULL REFERENCES plans(code) ON DELETE CASCADE,
  feature   varchar(50) NOT NULL,
  PRIMARY KEY (plan_code, feature)
);

CREATE TABLE subscriptions (
  id                   uuid PRIMARY KEY,
  business_id          uuid NOT NULL UNIQUE REFERENCES businesses(id) ON DELETE CASCADE,
  plan_code            varchar(30) NOT NULL REFERENCES plans(code),
  billing_cycle        varchar(10) NOT NULL,
  status               varchar(15) NOT NULL,
  current_period_start timestamptz NOT NULL,
  current_period_end   timestamptz NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE categories (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(80) NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_category_name ON categories(business_id, lower(name));

CREATE TABLE products (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  category_id uuid REFERENCES categories(id) ON DELETE SET NULL,
  name        varchar(200) NOT NULL,
  description text,
  image_url   varchar(500),
  status      varchar(15) NOT NULL DEFAULT 'ACTIVE',
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_business_status ON products(business_id, status);

-- Stock always lives on a variant. A simple product has exactly one variant with a NULL label.
CREATE TABLE product_variants (
  id                  uuid PRIMARY KEY,
  business_id         uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  product_id          uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  label               varchar(120),
  sku                 varchar(60),
  barcode             varchar(60),
  price               numeric(14,2) NOT NULL CHECK (price >= 0),
  cost                numeric(14,2) NOT NULL DEFAULT 0 CHECK (cost >= 0),
  quantity            integer NOT NULL DEFAULT 0,
  low_stock_threshold integer NOT NULL DEFAULT 5,
  active              boolean NOT NULL DEFAULT true,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_variants_product ON product_variants(product_id);
CREATE INDEX idx_variants_business ON product_variants(business_id);
CREATE UNIQUE INDEX uq_variant_sku ON product_variants(business_id, lower(sku)) WHERE sku IS NOT NULL;

CREATE TABLE stock_movements (
  id              uuid PRIMARY KEY,
  business_id     uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id      uuid NOT NULL REFERENCES product_variants(id),
  type            varchar(20) NOT NULL,
  quantity_change integer NOT NULL,
  balance_after   integer NOT NULL,
  note            varchar(300),
  reference       varchar(60),
  created_by      uuid,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_movements_business_time ON stock_movements(business_id, created_at DESC);
CREATE INDEX idx_movements_variant ON stock_movements(variant_id);

CREATE TABLE customers (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(150) NOT NULL,
  phone       varchar(40),
  email       varchar(255),
  address     text,
  notes       text,
  balance     numeric(14,2) NOT NULL DEFAULT 0,   -- money the customer owes the business
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_customers_business ON customers(business_id);

CREATE TABLE customer_payments (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  customer_id uuid NOT NULL REFERENCES customers(id),
  amount      numeric(14,2) NOT NULL CHECK (amount > 0),
  method      varchar(20) NOT NULL,
  note        varchar(300),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cust_payments_business_time ON customer_payments(business_id, created_at);
CREATE INDEX idx_cust_payments_customer ON customer_payments(customer_id);

CREATE TABLE sales (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  sale_number       bigint NOT NULL,
  customer_id       uuid REFERENCES customers(id),
  status            varchar(15) NOT NULL,
  subtotal          numeric(14,2) NOT NULL,
  discount          numeric(14,2) NOT NULL DEFAULT 0,
  total             numeric(14,2) NOT NULL,
  amount_paid       numeric(14,2) NOT NULL,
  amount_owed       numeric(14,2) NOT NULL,
  cost_total        numeric(14,2) NOT NULL,
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  refunded_at       timestamptz,
  UNIQUE (business_id, sale_number)
);
CREATE INDEX idx_sales_business_time ON sales(business_id, created_at DESC);
CREATE INDEX idx_sales_customer ON sales(customer_id);
CREATE UNIQUE INDEX uq_sales_client_request ON sales(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE sale_items (
  id            uuid PRIMARY KEY,
  sale_id       uuid NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
  business_id   uuid NOT NULL,
  variant_id    uuid NOT NULL REFERENCES product_variants(id),
  product_name  varchar(200) NOT NULL,
  variant_label varchar(120),
  quantity      integer NOT NULL CHECK (quantity > 0),
  unit_price    numeric(14,2) NOT NULL,
  unit_cost     numeric(14,2) NOT NULL,
  line_total    numeric(14,2) NOT NULL
);
CREATE INDEX idx_sale_items_sale ON sale_items(sale_id);

CREATE TABLE sale_payments (
  id          uuid PRIMARY KEY,
  sale_id     uuid NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
  business_id uuid NOT NULL,
  method      varchar(20) NOT NULL,
  amount      numeric(14,2) NOT NULL CHECK (amount > 0)
);
CREATE INDEX idx_sale_payments_sale ON sale_payments(sale_id);

CREATE TABLE expenses (
  id             uuid PRIMARY KEY,
  business_id    uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  category       varchar(80) NOT NULL,
  amount         numeric(14,2) NOT NULL CHECK (amount > 0),
  expense_date   date NOT NULL,
  description    varchar(300),
  payment_method varchar(20) NOT NULL,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenses_business_date ON expenses(business_id, expense_date DESC);

CREATE TABLE audit_logs (
  id          uuid PRIMARY KEY,
  business_id uuid,
  user_id     uuid,
  action      varchar(60) NOT NULL,
  entity      varchar(60),
  entity_id   varchar(64),
  detail      varchar(500),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_business_time ON audit_logs(business_id, created_at DESC);
