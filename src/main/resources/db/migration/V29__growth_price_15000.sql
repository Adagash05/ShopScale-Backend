-- Growth plan price: NGN 11,999 -> NGN 15,000 per month. Only the Growth price changes: limits, features, ordering and the
-- "recommended" flag are left exactly as V15 set them, and Essential, Business and Free are not touched.
-- Annual stays at ten months of the monthly price (the same two-months-free pattern as before: 11,999 x 10 = 119,990), so 15,000 x 10 = 150,000.
UPDATE plans SET monthly_price = 15000.00, annual_price = 150000.00 WHERE code = 'GROWTH';
