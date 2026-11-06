-- Launch plans. These are configuration rows, editable by the platform admin (never hardcode them in code).
-- max_items NULL = unlimited, subject to a fair-use policy.
INSERT INTO plans (code, name, monthly_price, annual_price, max_users, max_locations, max_items, sort_order) VALUES
 ('ESSENTIAL', 'Essential',  9000.00,  90000.00,  1, 1,  500, 1),
 ('GROWTH',    'Growth',    17000.00, 170000.00,  3, 2, 3000, 2),
 ('BUSINESS',  'Business',  29000.00, 290000.00, 10, 5, NULL, 3);

-- Shared by every plan
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f.feature FROM plans p
CROSS JOIN (VALUES ('POS'),('BASIC_INVENTORY'),('EXPENSES'),('BASIC_DASHBOARD'),('CUSTOMER_CREDIT'),
                   ('STOCK_HISTORY'),('STOREFRONT'),('ONLINE_ORDERS'),('DATA_EXPORT')) AS f(feature);

-- Growth and Business
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, f.feature FROM plans p
CROSS JOIN (VALUES ('ADVANCED_FINANCE'),('ENHANCED_DASHBOARD'),('SUPPLIER_BALANCES'),('STAFF_PERMISSIONS'),
                   ('ADVANCED_REPORTS')) AS f(feature)
WHERE p.code IN ('GROWTH','BUSINESS');

-- Business only
INSERT INTO plan_features (plan_code, feature) VALUES
 ('BUSINESS', 'LOCATION_REPORTING'),
 ('BUSINESS', 'ACTIVITY_HISTORY'),
 ('BUSINESS', 'CUSTOM_DOMAINS'),
 ('BUSINESS', 'ADVANCED_STAFF_PERMISSIONS'),
 ('BUSINESS', 'PRIORITY_SUPPORT');
