-- V15: final approved commercial configuration (FREE / ESSENTIAL / GROWTH / BUSINESS).
-- Plan rows and plan_features only. No subscription, payment, business or product record is touched; nothing is deleted
-- except plan_features rows that no longer apply. NULL = unlimited.

ALTER TABLE plans ADD COLUMN max_suppliers integer;                         -- NULL = unlimited
ALTER TABLE plans ADD COLUMN recommended   boolean NOT NULL DEFAULT false;

-- code, monthly, annual, users, locations, items, customers, expenses, suppliers
UPDATE plans SET monthly_price = 0,        annual_price = 0,         max_users = 1,  max_locations = 1, max_items = 15,   max_customers = 15,   max_expenses = 15,   max_suppliers = 5,    active = true, sort_order = 0 WHERE code = 'FREE';
UPDATE plans SET monthly_price = 4999.00,  annual_price = 49990.00,  max_users = 2,  max_locations = 1, max_items = 100,  max_customers = 100,  max_expenses = 100,  max_suppliers = 20,   active = true, sort_order = 1 WHERE code = 'ESSENTIAL';
UPDATE plans SET monthly_price = 11999.00, annual_price = 119990.00, max_users = 5,  max_locations = 2, max_items = 500,  max_customers = NULL, max_expenses = NULL, max_suppliers = 50,    active = true, sort_order = 2, recommended = true WHERE code = 'GROWTH';
UPDATE plans SET monthly_price = 24999.00, annual_price = 249990.00, max_users = 10, max_locations = 5, max_items = NULL, max_customers = NULL, max_expenses = NULL, max_suppliers = NULL, active = true, sort_order = 3 WHERE code = 'BUSINESS';
-- STARTER is obsolete: hidden from new signups; the row stays so any reference to it keeps working.
UPDATE plans SET active = false WHERE code = 'STARTER';

-- Feature distribution. Rebuilt for the four plans only (STARTER keeps whatever it had).
DELETE FROM plan_features WHERE plan_code IN ('FREE','ESSENTIAL','GROWTH','BUSINESS')
  AND feature IN ('POS','BASIC_INVENTORY','EXPENSES','BASIC_DASHBOARD','CUSTOMER_CREDIT','STOCK_HISTORY','DATA_EXPORT','PURCHASING','SUPPLIER_BALANCES',
                  'CASH_ACCOUNTS','ENHANCED_DASHBOARD','WHOLESALE_PRICING','STOREFRONT','ONLINE_ORDERS','BRANCH_TRANSFERS','STAFF_PERMISSIONS','ADVANCED_REPORTS',
                  'ADVANCED_INVENTORY','LOCATION_REPORTING','CASH_RECONCILIATION','ADVANCED_FINANCE','ADVANCED_STAFF_PERMISSIONS','CUSTOM_DOMAINS','PRIORITY_SUPPORT',
                  'ACTIVITY_HISTORY','CONSOLIDATED_REPORTING');

-- Free: basic POS, stock, expenses, customers and credit, dashboard. No export, purchasing or supplier balances.
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['POS','BASIC_INVENTORY','EXPENSES','BASIC_DASHBOARD','CUSTOMER_CREDIT','STOCK_HISTORY']) AS f
WHERE p.code IN ('FREE','ESSENTIAL','GROWTH','BUSINESS');

-- Essential and above
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['DATA_EXPORT','PURCHASING','SUPPLIER_BALANCES','CASH_ACCOUNTS','ENHANCED_DASHBOARD','WHOLESALE_PRICING']) AS f
WHERE p.code IN ('ESSENTIAL','GROWTH','BUSINESS');

-- Growth and above: online store, branches, staff, advanced inventory and reporting
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f FROM plans p CROSS JOIN unnest(ARRAY['STOREFRONT','ONLINE_ORDERS','BRANCH_TRANSFERS','STAFF_PERMISSIONS','ADVANCED_REPORTS','ADVANCED_INVENTORY','LOCATION_REPORTING','CASH_RECONCILIATION']) AS f
WHERE p.code IN ('GROWTH','BUSINESS');

-- Business only
INSERT INTO plan_features (plan_code, feature)
SELECT 'BUSINESS', f FROM unnest(ARRAY['ADVANCED_FINANCE','ADVANCED_STAFF_PERMISSIONS','CUSTOM_DOMAINS','PRIORITY_SUPPORT','ACTIVITY_HISTORY','CONSOLIDATED_REPORTING']) AS f;
