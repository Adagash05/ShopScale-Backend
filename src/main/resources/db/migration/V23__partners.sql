-- ShopScale Partners: affiliate programme (applications, referral attribution, commission ledger, manual payouts, audit).
-- Money is numeric(14,2). Nothing here changes an existing table.

-- ---------- programme settings (one row) ----------
CREATE TABLE partner_settings (
    id                 smallint PRIMARY KEY CHECK (id = 1),
    commission_rate    numeric(5,4)  NOT NULL DEFAULT 0.3000 CHECK (commission_rate > 0 AND commission_rate <= 1),
    commission_months  integer       NOT NULL DEFAULT 12     CHECK (commission_months BETWEEN 1 AND 60),
    attribution_days   integer       NOT NULL DEFAULT 30     CHECK (attribution_days BETWEEN 1 AND 365),
    holding_days       integer       NOT NULL DEFAULT 14     CHECK (holding_days BETWEEN 0 AND 120),
    min_withdrawal     numeric(14,2) NOT NULL DEFAULT 10000  CHECK (min_withdrawal >= 0),
    active             boolean       NOT NULL DEFAULT true,
    terms_version      varchar(20)   NOT NULL DEFAULT '1',
    updated_by         uuid,
    updated_at         timestamptz   NOT NULL DEFAULT now()
);
INSERT INTO partner_settings (id) VALUES (1);

-- ---------- affiliates (an application and, once approved, the partner) ----------
CREATE TABLE affiliates (
    id                 uuid PRIMARY KEY,
    user_id            uuid         NOT NULL UNIQUE REFERENCES users(id),
    code               varchar(24)  UNIQUE,                       -- issued on approval
    status             varchar(12)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
    full_name          varchar(150) NOT NULL,
    email              varchar(255) NOT NULL,
    phone              varchar(40)  NOT NULL,
    country            varchar(80)  NOT NULL,
    location           varchar(120),
    socials            text,                                      -- one "Platform: https://..." per line
    audience_size      integer      NOT NULL CHECK (audience_size >= 0),
    niche              varchar(120) NOT NULL,
    reason             varchar(1000) NOT NULL,
    website            varchar(255),
    terms_accepted_at  timestamptz  NOT NULL,
    terms_version      varchar(20)  NOT NULL,
    status_reason      varchar(500),
    reviewed_by        uuid,
    reviewed_at        timestamptz,
    approved_at        timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_affiliates_status ON affiliates(status, created_at DESC);

CREATE TABLE affiliate_bank_accounts (
    affiliate_id          uuid PRIMARY KEY REFERENCES affiliates(id),
    holder_name           varchar(150) NOT NULL,
    bank_name             varchar(100) NOT NULL,
    bank_code             varchar(20),
    account_number_enc    text         NOT NULL,                  -- AES-GCM (SecretBox); never returned by the API
    account_last4         varchar(4)   NOT NULL,
    updated_at            timestamptz  NOT NULL DEFAULT now()
);

-- ---------- clicks and referrals ----------
CREATE TABLE affiliate_clicks (
    id            uuid PRIMARY KEY,
    affiliate_id  uuid         NOT NULL REFERENCES affiliates(id),
    visitor_id    varchar(64)  NOT NULL,
    click_day     date         NOT NULL,
    clicked_at    timestamptz  NOT NULL DEFAULT now(),
    source        varchar(120),
    referrer_host varchar(120),
    ip_hash       varchar(64),
    user_agent    varchar(200)
);
-- One counted click per visitor, per partner, per day: reloading a link does not inflate the numbers.
CREATE UNIQUE INDEX uq_affiliate_clicks_day ON affiliate_clicks(affiliate_id, visitor_id, click_day);
CREATE INDEX idx_affiliate_clicks_visitor ON affiliate_clicks(visitor_id, clicked_at DESC);

CREATE TABLE affiliate_referrals (
    id                 uuid PRIMARY KEY,
    affiliate_id       uuid         NOT NULL REFERENCES affiliates(id),
    referred_user_id   uuid         NOT NULL UNIQUE REFERENCES users(id),   -- a customer is attributed once, forever
    click_id           uuid         REFERENCES affiliate_clicks(id),
    attribution_source varchar(8)   NOT NULL CHECK (attribution_source IN ('CLICK','CODE')),
    click_at           timestamptz,
    attributed_at      timestamptz  NOT NULL DEFAULT now(),
    -- Terms frozen when the customer registered: later settings changes never alter them.
    rate               numeric(5,4) NOT NULL,
    commission_months  integer      NOT NULL,
    holding_days       integer      NOT NULL,
    first_paid_at      timestamptz,
    eligible_until     timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_affiliate_referrals_affiliate ON affiliate_referrals(affiliate_id, attributed_at DESC);

-- ---------- withdrawals ----------
CREATE TABLE affiliate_withdrawals (
    id                   uuid PRIMARY KEY,
    affiliate_id         uuid          NOT NULL REFERENCES affiliates(id),
    amount               numeric(14,2) NOT NULL CHECK (amount > 0),
    currency             varchar(3)    NOT NULL DEFAULT 'NGN',
    status               varchar(12)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','PROCESSING','PAID','REJECTED','FAILED','CANCELLED')),
    payout_method        varchar(20)   NOT NULL DEFAULT 'MANUAL_BANK',   -- a future provider (e.g. Paystack Transfers) adds a value here
    provider_reference   varchar(80),
    -- Bank details as they were when the request was made.
    bank_holder_name     varchar(150)  NOT NULL,
    bank_name            varchar(100)  NOT NULL,
    bank_code            varchar(20),
    account_number_enc   text          NOT NULL,
    account_last4        varchar(4)    NOT NULL,
    requested_at         timestamptz   NOT NULL DEFAULT now(),
    approved_at          timestamptz,
    approved_by          uuid,
    processing_at        timestamptz,
    processing_by        uuid,
    paid_at              timestamptz,
    paid_by              uuid,
    transfer_reference   varchar(80),
    payout_notes         varchar(500),
    closed_reason        varchar(500),
    closed_by            uuid,
    closed_at            timestamptz,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_withdrawal_paid CHECK (status <> 'PAID' OR (transfer_reference IS NOT NULL AND paid_at IS NOT NULL AND paid_by IS NOT NULL))
);
-- One open withdrawal per partner, enforced by the database even under concurrent requests.
CREATE UNIQUE INDEX uq_affiliate_withdrawal_open ON affiliate_withdrawals(affiliate_id) WHERE status IN ('REQUESTED','APPROVED','PROCESSING');
-- A bank transfer reference can pay out only one withdrawal.
CREATE UNIQUE INDEX uq_affiliate_withdrawal_transfer ON affiliate_withdrawals(lower(transfer_reference)) WHERE transfer_reference IS NOT NULL;
CREATE INDEX idx_affiliate_withdrawals_status ON affiliate_withdrawals(status, requested_at DESC);

-- ---------- commission ledger ----------
-- One EARNED row per verified subscription payment (unique payment_id = never paid twice). Rows change status only
-- through the service; every change is also written to affiliate_commission_events, which can never be edited.
CREATE TABLE affiliate_commissions (
    id                uuid PRIMARY KEY,
    affiliate_id      uuid          NOT NULL REFERENCES affiliates(id),
    referral_id       uuid          REFERENCES affiliate_referrals(id),
    payment_id        uuid          UNIQUE REFERENCES subscription_payments(id),
    kind              varchar(10)   NOT NULL DEFAULT 'EARNED' CHECK (kind IN ('EARNED','ADJUSTMENT','CLAWBACK')),
    status            varchar(10)   NOT NULL CHECK (status IN ('PENDING','AVAILABLE','RESERVED','PAID','REVERSED','CANCELLED')),
    payment_amount    numeric(14,2),
    rate              numeric(5,4),
    eligible_fraction numeric(7,6),
    amount            numeric(14,2) NOT NULL,
    reversed_amount   numeric(14,2) NOT NULL DEFAULT 0,
    currency          varchar(3)    NOT NULL DEFAULT 'NGN',
    earned_at         timestamptz   NOT NULL DEFAULT now(),
    available_at      timestamptz   NOT NULL,
    withdrawal_id     uuid          REFERENCES affiliate_withdrawals(id),
    reason            varchar(500),
    created_by        uuid,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_commission_amounts CHECK (
        (kind = 'CLAWBACK' AND amount < 0 AND reversed_amount = 0)
        OR (kind <> 'CLAWBACK' AND amount >= 0 AND reversed_amount >= 0 AND reversed_amount <= amount)),
    CONSTRAINT chk_commission_earned_payment CHECK (kind <> 'EARNED' OR (payment_id IS NOT NULL AND referral_id IS NOT NULL)),
    CONSTRAINT chk_commission_reserved CHECK ((status IN ('RESERVED','PAID')) = (withdrawal_id IS NOT NULL) OR status IN ('REVERSED','CANCELLED'))
);
CREATE INDEX idx_affiliate_commissions_affiliate ON affiliate_commissions(affiliate_id, status);
CREATE INDEX idx_affiliate_commissions_due ON affiliate_commissions(available_at) WHERE status = 'PENDING';
CREATE INDEX idx_affiliate_commissions_withdrawal ON affiliate_commissions(withdrawal_id) WHERE withdrawal_id IS NOT NULL;
CREATE INDEX idx_affiliate_commissions_referral ON affiliate_commissions(referral_id);

CREATE TABLE affiliate_commission_events (
    id             uuid PRIMARY KEY,
    commission_id  uuid          NOT NULL REFERENCES affiliate_commissions(id),
    affiliate_id   uuid          NOT NULL REFERENCES affiliates(id),
    event          varchar(20)   NOT NULL,
    from_status    varchar(10),
    to_status      varchar(10),
    amount         numeric(14,2),
    withdrawal_id  uuid,
    actor_user_id  uuid,
    reason         varchar(500),
    created_at     timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_commission_events_commission ON affiliate_commission_events(commission_id, created_at);

-- Refund / chargeback notices already applied (Paystack can deliver the same notice more than once).
CREATE TABLE affiliate_refund_events (
    id           uuid PRIMARY KEY,
    payment_id   uuid          NOT NULL REFERENCES subscription_payments(id),
    reference    varchar(100)  NOT NULL UNIQUE,
    source       varchar(12)   NOT NULL,
    amount       numeric(14,2) NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now()
);

-- ---------- audit ----------
CREATE TABLE affiliate_audit_log (
    id             uuid PRIMARY KEY,
    actor_user_id  uuid,
    action         varchar(40)  NOT NULL,
    entity         varchar(30)  NOT NULL,
    entity_id      uuid,
    detail         varchar(1000),
    created_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_affiliate_audit_entity ON affiliate_audit_log(entity, entity_id, created_at DESC);

-- Financial history is append-only.
CREATE FUNCTION partners_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; record a correcting entry instead', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_commission_events_immutable BEFORE UPDATE OR DELETE ON affiliate_commission_events FOR EACH ROW EXECUTE FUNCTION partners_append_only();
CREATE TRIGGER trg_refund_events_immutable BEFORE UPDATE OR DELETE ON affiliate_refund_events FOR EACH ROW EXECUTE FUNCTION partners_append_only();
CREATE TRIGGER trg_affiliate_audit_immutable BEFORE UPDATE OR DELETE ON affiliate_audit_log FOR EACH ROW EXECUTE FUNCTION partners_append_only();
-- Commission rows are never deleted.
CREATE TRIGGER trg_commissions_no_delete BEFORE DELETE ON affiliate_commissions FOR EACH ROW EXECUTE FUNCTION partners_append_only();

-- ---------- partner resources (managed by administrators) ----------
CREATE TABLE partner_resources (
    id          uuid PRIMARY KEY,
    kind        varchar(8)    NOT NULL CHECK (kind IN ('GUIDE','FAQ','LINK','ASSET')),
    title       varchar(160)  NOT NULL,
    body        text,
    url         varchar(500),
    position    integer       NOT NULL DEFAULT 0,
    published   boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);

INSERT INTO partner_resources (id, kind, title, body, position) VALUES
 ('7b1f0a10-0000-4000-8000-000000000001','GUIDE','What ShopScale does',
  'ShopScale is a business management platform for Nigerian small businesses: point-of-sale and sales records, stock and products, customers and debts, expenses, suppliers and purchases, invoices and receipts shared on WhatsApp, a team with roles, and an online store customers can order from. It works offline for selling and syncs when the connection returns. Retail shops, restaurants and caterers each get tools shaped for how they work. Show the real product when you talk about it.', 1),
 ('7b1f0a10-0000-4000-8000-000000000002','GUIDE','How you earn',
  'You earn the commission rate set in the programme settings on the subscription payments ShopScale actually receives from businesses that registered through your link, for the first months of their subscription (shown on your dashboard). Registrations, clicks and free use earn nothing. Refunded payments are reversed. Commission is held for the holding period, then becomes available to withdraw once your available balance reaches the minimum. Payouts are manual bank transfers recorded by ShopScale with a transfer reference.', 2),
 ('7b1f0a10-0000-4000-8000-000000000003','GUIDE','Content guidelines',
  'Show genuine ShopScale features, ideally your own screen recording or a live walkthrough. Do not promise income, profits or sales results to the businesses you refer, and do not invent testimonials or statistics. Say clearly that you are a ShopScale partner and may earn a commission. Do not use misleading ads, impersonate ShopScale, bid on the ShopScale brand name in paid search, or send unsolicited bulk messages. Do not register businesses yourself to earn commission; self-referrals are not paid.', 3),
 ('7b1f0a10-0000-4000-8000-000000000004','FAQ','When is my commission available?',
  'After the holding period (shown on your dashboard) has passed since the customer''s payment. Until then it is shown as pending.', 4),
 ('7b1f0a10-0000-4000-8000-000000000005','FAQ','What if a customer is refunded?',
  'The matching commission is reversed. If it was already paid out, the amount is deducted from your future earnings.', 5),
 ('7b1f0a10-0000-4000-8000-000000000006','FAQ','How do I get paid?',
  'Add your bank details, then request a withdrawal when your available balance reaches the minimum. ShopScale reviews the request and sends a bank transfer, then records the transfer reference so you can see it in your withdrawal history.', 6);
