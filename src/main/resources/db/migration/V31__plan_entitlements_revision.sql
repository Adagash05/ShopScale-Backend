-- V31: revised Free / Essential / Growth / Business allowances and features (single source of truth: plans + plan_features).
-- Prices, subscriptions, payments and customer records are NOT touched. No business data is deleted: a business that is above a
-- lower limit keeps everything it has and simply cannot add more until it is back within the allowance.
-- NULL = unlimited. max_whatsapp_reminders = 0 means none.

ALTER TABLE plans ADD COLUMN IF NOT EXISTS max_whatsapp_reminders integer;                       -- per billing month; NULL = unlimited
ALTER TABLE plans ADD COLUMN IF NOT EXISTS expense_period varchar(10) NOT NULL DEFAULT 'TOTAL';  -- TOTAL = lifetime count, MONTH = per billing month
ALTER TABLE plans DROP CONSTRAINT IF EXISTS chk_plans_expense_period;
ALTER TABLE plans ADD CONSTRAINT chk_plans_expense_period CHECK (expense_period IN ('TOTAL', 'MONTH'));

-- code: users, products, customers, expenses, suppliers, reminders / month, expense period (locations are unchanged)
UPDATE plans SET max_users = 1,  max_items = 10,   max_customers = 10,   max_expenses = 10,   max_suppliers = 0,    max_whatsapp_reminders = 0,    expense_period = 'TOTAL' WHERE code = 'FREE';
UPDATE plans SET max_users = 2,  max_items = 50,   max_customers = 50,   max_expenses = 50,   max_suppliers = 10,   max_whatsapp_reminders = 20,   expense_period = 'MONTH' WHERE code = 'ESSENTIAL';
UPDATE plans SET max_users = 5,  max_items = 200,  max_customers = NULL, max_expenses = NULL, max_suppliers = 50,   max_whatsapp_reminders = 50,   expense_period = 'TOTAL' WHERE code = 'GROWTH';
UPDATE plans SET max_users = 10, max_items = NULL, max_customers = NULL, max_expenses = NULL, max_suppliers = NULL, max_whatsapp_reminders = NULL, expense_period = 'TOTAL' WHERE code = 'BUSINESS';
-- STARTER is obsolete and hidden; it must never read as "unlimited reminders".
UPDATE plans SET max_whatsapp_reminders = 0 WHERE code = 'STARTER';

-- Features. Removed from Essential: CSV export and Ask ShopScale. Activity history moves from Business-only to Growth and Business.
DELETE FROM plan_features WHERE plan_code = 'ESSENTIAL' AND feature IN ('DATA_EXPORT', 'ASK_SHOPSCALE');
DELETE FROM plan_features WHERE plan_code = 'FREE' AND feature IN ('DATA_EXPORT', 'ASK_SHOPSCALE', 'ACTIVITY_HISTORY', 'STOREFRONT', 'ONLINE_ORDERS',
  'BEST_SELLING_PRODUCTS', 'BEST_SELLING_CUSTOMERS', 'WHATSAPP_REMINDERS', 'PURCHASING', 'SUPPLIER_BALANCES', 'CASH_ACCOUNTS');

-- Essential and above: WhatsApp invoice reminders (within the monthly allowance above)
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, 'WHATSAPP_REMINDERS' FROM plans p WHERE p.code IN ('ESSENTIAL', 'GROWTH', 'BUSINESS')
ON CONFLICT (plan_code, feature) DO NOTHING;

-- Growth and above
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['DATA_EXPORT', 'ASK_SHOPSCALE', 'BEST_SELLING_PRODUCTS', 'BEST_SELLING_CUSTOMERS',
                                                    'ACTIVITY_HISTORY', 'STOREFRONT', 'ONLINE_ORDERS']) AS f
WHERE p.code IN ('GROWTH', 'BUSINESS')
ON CONFLICT (plan_code, feature) DO NOTHING;

-- One row per reminder that was handed to WhatsApp (or copied). request_id makes a repeated request count once.
CREATE TABLE IF NOT EXISTS reminder_log (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  request_id  varchar(64) NOT NULL,
  channel     varchar(20) NOT NULL DEFAULT 'WHATSAPP',
  invoice_id  uuid,
  customer_id uuid,
  user_id     uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_reminder_request UNIQUE (business_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_reminder_log_business_time ON reminder_log (business_id, created_at);
