-- Wholesale: customer types, price tiers, bulk breaks and customer-specific prices.
CREATE TABLE price_tiers (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(80) NOT NULL,
  active      boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, name)
);

ALTER TABLE customers ADD COLUMN customer_type varchar(20) NOT NULL DEFAULT 'RETAIL';
ALTER TABLE customers ADD COLUMN price_tier_id uuid REFERENCES price_tiers(id) ON DELETE SET NULL;

-- One row = one price rule for a variant.
--   customer_id set          -> price agreed with that customer
--   else price_tier_id set   -> price for every customer on that tier
--   else                     -> bulk break for everyone
-- min_quantity (in the variant's base unit) lets any rule be a volume break.
CREATE TABLE variant_prices (
  id            uuid PRIMARY KEY,
  business_id   uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  variant_id    uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  price_tier_id uuid REFERENCES price_tiers(id) ON DELETE CASCADE,
  customer_id   uuid REFERENCES customers(id) ON DELETE CASCADE,
  min_quantity  numeric(14,3) NOT NULL DEFAULT 1 CHECK (min_quantity > 0),
  price         numeric(14,2) NOT NULL CHECK (price >= 0),
  created_at    timestamptz NOT NULL DEFAULT now(),
  CHECK (NOT (price_tier_id IS NOT NULL AND customer_id IS NOT NULL))
);
CREATE INDEX idx_variant_prices_variant ON variant_prices(business_id, variant_id);
CREATE UNIQUE INDEX uq_variant_prices_rule ON variant_prices(variant_id, COALESCE(price_tier_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(customer_id, '00000000-0000-0000-0000-000000000000'::uuid), min_quantity);

INSERT INTO plan_features (plan_code, feature)
SELECT code, 'WHOLESALE_PRICING' FROM plans WHERE code IN ('GROWTH','BUSINESS','ESSENTIAL') ON CONFLICT DO NOTHING;
