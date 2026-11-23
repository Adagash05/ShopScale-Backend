-- V27: "Ask ShopScale" (structured business questions answered from the business's own records) becomes a plan feature.
-- Data only: three plan_features rows. No plan price, limit, subscription or customer record is touched, and nothing is removed.
-- Same tiers as the advanced dashboard (Essential and above); Free keeps the basic dashboard. To change which plans get it,
-- add or delete rows here, e.g.  DELETE FROM plan_features WHERE feature = 'ASK_SHOPSCALE' AND plan_code = 'ESSENTIAL';
INSERT INTO plan_features (plan_code, feature)
SELECT p.code, 'ASK_SHOPSCALE' FROM plans p WHERE p.code IN ('ESSENTIAL', 'GROWTH', 'BUSINESS')
ON CONFLICT (plan_code, feature) DO NOTHING;
