-- V20: storefront extras. Logo, social links, opening hours, sale prices, extra photos, specifications,
-- verified-purchase reviews, newsletter, catering enquiries and paid menu options on online orders.

ALTER TABLE store_settings
  ADD COLUMN logo_type     varchar(30),
  ADD COLUMN instagram_url varchar(200),
  ADD COLUMN facebook_url  varchar(200),
  ADD COLUMN x_url         varchar(200),
  ADD COLUMN tiktok_url    varchar(200),
  ADD COLUMN opening_hours varchar(600);

-- Logo bytes live in their own table so reading store settings never loads the picture.
CREATE TABLE store_logos (
  business_id uuid PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
  data        bytea NOT NULL
);

-- A sale price is shown only while compare_at_price is higher than price; the price actually charged is always `price`.
ALTER TABLE product_variants ADD COLUMN compare_at_price numeric(14,2) CHECK (compare_at_price IS NULL OR compare_at_price > 0);
ALTER TABLE products ADD COLUMN specs varchar(2000);

-- Extra photos for a product's gallery (the main photo is still products.image_url).
ALTER TABLE product_images ADD COLUMN gallery boolean NOT NULL DEFAULT false;
ALTER TABLE product_images ADD COLUMN position integer NOT NULL DEFAULT 0;

CREATE TABLE store_reviews (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  product_id  uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  order_id    uuid NOT NULL REFERENCES store_orders(id) ON DELETE CASCADE,
  author_name varchar(80) NOT NULL,
  rating      smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
  body        varchar(1000) NOT NULL,
  status      varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','HIDDEN')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (order_id, product_id)
);
CREATE INDEX idx_store_reviews_product ON store_reviews(business_id, product_id, status);

CREATE TABLE store_subscribers (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  email       varchar(150) NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_store_subscriber ON store_subscribers(business_id, lower(email));

CREATE TABLE store_enquiries (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(120) NOT NULL,
  phone       varchar(30) NOT NULL,
  email       varchar(150),
  event_date  date,
  guests      integer CHECK (guests IS NULL OR guests > 0),
  message     varchar(1500) NOT NULL,
  status      varchar(12) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CONTACTED','CLOSED')),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_enquiries_business ON store_enquiries(business_id, created_at DESC);

-- Paid menu options chosen on an online order. Names and prices are copied so later menu edits never change a past order.
ALTER TABLE store_order_items ADD COLUMN options_total numeric(14,2) NOT NULL DEFAULT 0;
CREATE TABLE store_order_item_options (
  id          uuid PRIMARY KEY,
  item_id     uuid NOT NULL REFERENCES store_order_items(id) ON DELETE CASCADE,
  modifier_id uuid NOT NULL REFERENCES modifiers(id),
  name        varchar(80) NOT NULL,
  price_delta numeric(14,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_store_order_item_options_item ON store_order_item_options(item_id);
