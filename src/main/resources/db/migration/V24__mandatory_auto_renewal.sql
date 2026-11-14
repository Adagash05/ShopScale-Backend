-- Automatic renewal is now the rule for every paid plan (no opt-in, no one-time payment).
-- New payments switch it on in BillingService.settle. This migration brings EXISTING subscriptions in line, and only where that is safe and fair:
--   * the plan is paid, ACTIVE and still inside its paid period;
--   * a reusable payment method is already saved and active (nothing can be charged without one);
--   * the customer has never cancelled or turned renewal off (audit_logs has no SUBSCRIPTION_CANCELLED / AUTO_RENEW_OFF for the business);
--   * the most recent renewal attempt did not end in a final failure (those need the customer to act).
-- Everything else is left exactly as it is, so explicit decisions are never overridden. Subscriptions without a saved card cannot be charged:
-- the Plan page asks them to add one, and their next payment saves it and turns renewal on. Safe to run more than once.
WITH eligible AS (
    SELECT s.id AS subscription_id, s.business_id, a.consented_at
    FROM subscriptions s
    JOIN billing_authorizations a ON a.business_id = s.business_id AND a.active AND a.reusable
    WHERE s.auto_renew = false
      AND s.status = 'ACTIVE'
      AND s.plan_code <> 'FREE'
      AND s.current_period_end > now()
      AND NOT EXISTS (SELECT 1 FROM audit_logs l WHERE l.business_id = s.business_id AND l.action IN ('SUBSCRIPTION_CANCELLED', 'AUTO_RENEW_OFF'))
      AND NOT EXISTS (
          SELECT 1 FROM (SELECT r.status, r.next_retry_at FROM subscription_renewals r WHERE r.subscription_id = s.id ORDER BY r.created_at DESC LIMIT 1) last
          WHERE last.status = 'FAILED' AND last.next_retry_at IS NULL)
), upd AS (
    UPDATE subscriptions s
       SET auto_renew = true,
           auto_renew_consent_at = COALESCE(s.auto_renew_consent_at, e.consented_at),
           updated_at = now()
      FROM eligible e
     WHERE s.id = e.subscription_id
    RETURNING s.id, s.business_id, s.plan_code
)
INSERT INTO audit_logs (id, business_id, user_id, action, entity, entity_id, detail)
SELECT gen_random_uuid(), business_id, NULL, 'AUTO_RENEW_ON', 'subscription', id::text, left(plan_code || ' (automatic renewal is now standard for paid plans)', 500) FROM upd;
