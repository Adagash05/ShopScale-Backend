-- V13: online storefront, merchant-owned Paystack connection, online orders, product images.

CREATE TABLE store_settings (
  business_id          uuid PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
  enabled              boolean NOT NULL DEFAULT false,
  title                varchar(120),
  description          varchar(500),
  phone                varchar(30),
  whatsapp             varchar(30),
  pickup_enabled       boolean NOT NULL DEFAULT true,
  delivery_enabled     boolean NOT NULL DEFAULT false,
  delivery_fee         numeric(14,2) NOT NULL DEFAULT 0 CHECK (delivery_fee >= 0),
  delivery_note        varchar(300),
  pay_later_enabled    boolean NOT NULL DEFAULT true,
  branch_id            uuid REFERENCES branches(id),
  -- The merchant's own Paystack account. The secret key is stored encrypted and is never returned by any endpoint.
  paystack_public_key  varchar(100),
  paystack_secret_enc  text,
  paystack_connected_at timestamptz,
  custom_domain        varchar(150) UNIQUE,
  domain_token         varchar(64),
  domain_verified      boolean NOT NULL DEFAULT false,
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE store_orders (
  id                 uuid PRIMARY KEY,
  business_id        uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  number             bigint NOT NULL,
  public_token       varchar(64) NOT NULL UNIQUE,
  customer_name      varchar(150) NOT NULL,
  phone              varchar(30) NOT NULL,
  email              varchar(150),
  delivery_method    varchar(10) NOT NULL CHECK (delivery_method IN ('PICKUP','DELIVERY')),
  address            varchar(300),
  note               varchar(500),
  status             varchar(16) NOT NULL CHECK (status IN ('PENDING_PAYMENT','NEW','CONFIRMED','READY','COMPLETED','CANCELLED')),
  payment_mode       varchar(10) NOT NULL CHECK (payment_mode IN ('ONLINE','PAY_LATER')),
  payment_status     varchar(10) NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PAID','FAILED')),
  subtotal           numeric(14,2) NOT NULL,
  delivery_fee       numeric(14,2) NOT NULL DEFAULT 0,
  total              numeric(14,2) NOT NULL,
  paystack_reference varchar(64) UNIQUE,
  paid_at            timestamptz,
  sale_id            uuid REFERENCES sales(id),
  branch_id          uuid NOT NULL REFERENCES branches(id),
  attention          varchar(300),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, number)
);
CREATE INDEX idx_store_orders_business ON store_orders(business_id, status, created_at DESC);

CREATE TABLE store_order_items (
  id          uuid PRIMARY KEY,
  order_id    uuid NOT NULL REFERENCES store_orders(id) ON DELETE CASCADE,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id  uuid NOT NULL REFERENCES product_variants(id),
  item_name   varchar(250) NOT NULL,
  quantity    numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_price  numeric(14,2) NOT NULL,
  line_total  numeric(14,2) NOT NULL
);
CREATE INDEX idx_store_order_items_order ON store_order_items(order_id);

-- Product pictures are kept in the database (resized in the browser before upload) so the app needs no extra storage service.
CREATE TABLE product_images (
  id           uuid PRIMARY KEY,
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  product_id   uuid REFERENCES products(id) ON DELETE CASCADE,
  content_type varchar(30) NOT NULL,
  size_bytes   integer NOT NULL,
  data         bytea NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_images_product ON product_images(product_id);

