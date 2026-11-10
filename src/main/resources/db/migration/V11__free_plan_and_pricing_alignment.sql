-- V11: permanent Free plan, Essential user limit 1 -> 2, and alignment of paid plans with the approved commercial
-- configuration (ESSENTIAL 9,000 / GROWTH 17,000 / BUSINESS 29,000 per month, as seeded in V2).
-- V5 had applied a different "launch pricing" set; this migration restores the approved configuration. It only changes
-- plan rows; no subscription, payment or business record is touched, and nothing is deleted.

ALTER TABLE plans ADD COLUMN max_customers integer;   -- NULL = unlimited
ALTER TABLE plans ADD COLUMN max_expenses  integer;   -- NULL = unlimited

UPDATE plans SET active = true,  sort_order = 1, max_users = 2 WHERE code = 'ESSENTIAL';
UPDATE plans SET monthly_price = 17000.00, annual_price = 170000.00, max_users = 3, max_locations = 2, max_items = 3000, sort_order = 2 WHERE code = 'GROWTH';
UPDATE plans SET monthly_price = 29000.00, annual_price = 290000.00, max_users = 10, max_locations = 5, max_items = NULL, sort_order = 3 WHERE code = 'BUSINESS';
-- STARTER (introduced by V5) is not part of the approved plans. The row is kept, hidden, so nothing that references it breaks.
UPDATE plans SET active = false WHERE code = 'STARTER';

INSERT INTO plans (code, name, monthly_price, annual_price, max_users, max_locations, max_items, max_customers, max_expenses, sort_order, active)
VALUES ('FREE', 'Free', 0.00, 0.00, 1, 1, 10, 10, 10, 0, true);

INSERT INTO plan_features (plan_code, feature)
SELECT 'FREE', f FROM unnest(ARRAY['POS','BASIC_INVENTORY','EXPENSES','BASIC_DASHBOARD','CUSTOMER_CREDIT','STOCK_HISTORY','DATA_EXPORT']) AS f;
