-- Growth plan price: NGN 14,999 per month (supersedes the 15,000 set by V29, which is left untouched).
-- Annual stays at ten months of the monthly price, as before (11,999 x 10 = 119,990), so 14,999 x 10 = 149,990.
-- Only the Growth price changes. Limits, features, ordering and the "recommended" flag stay as V15 set them; Free, Essential and Business are not touched.
UPDATE plans SET monthly_price = 14999.00, annual_price = 149990.00 WHERE code = 'GROWTH';

-- Existing subscribers keep the price they have been paying. A subscription may carry an agreed price for one plan and one billing cycle;
-- it is used for renewals (automatic or manual) and for the credit given on a plan change, and it is dropped as soon as the subscriber
-- chooses a different plan or cycle. New subscribers, and anyone who later returns after a lapse, pay the current plan price.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS locked_plan_code varchar(30);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS locked_cycle      varchar(20);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS locked_price      numeric(14,2);
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_locked_price CHECK (locked_price IS NULL OR (locked_price > 0 AND locked_plan_code IS NOT NULL AND locked_cycle IS NOT NULL));

-- Current paying Growth subscribers (they have at least one successful payment) keep 11,999 monthly / 119,990 annual.
UPDATE subscriptions s
   SET locked_plan_code = 'GROWTH',
       locked_cycle     = s.billing_cycle,
       locked_price     = CASE WHEN s.billing_cycle = 'ANNUAL' THEN 119990.00 ELSE 11999.00 END
 WHERE s.plan_code = 'GROWTH'
   AND s.status IN ('ACTIVE', 'PAST_DUE', 'GRACE_PERIOD', 'CANCELLED', 'SUSPENDED')
   AND EXISTS (SELECT 1 FROM subscription_payments p WHERE p.business_id = s.business_id AND p.status = 'SUCCESS');
