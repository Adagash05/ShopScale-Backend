-- V19: automatic renewal for ShopScale subscriptions (AMSATI Paystack account). Extends V14; nothing is dropped or rewritten.
-- Existing subscriptions and payments keep working: every new column has a safe default (auto_renew = false).

ALTER TABLE subscriptions
    ADD COLUMN auto_renew        boolean     NOT NULL DEFAULT false,
    ADD COLUMN billing_anchor    timestamptz,            -- start of the first period of the current paid chain; renewals keep its day-of-month
    ADD COLUMN cancelled_at      timestamptz,
    ADD COLUMN auto_renew_consent_at timestamptz;

ALTER TABLE subscription_payments
    ADD COLUMN customer_email      varchar(254),          -- the email sent to Paystack; Paystack only lets that email charge the authorization
    ADD COLUMN auto_renew_consent  boolean     NOT NULL DEFAULT false,
    ADD COLUMN consent_at          timestamptz,
    ADD COLUMN source              varchar(12) NOT NULL DEFAULT 'CHECKOUT',   -- CHECKOUT (customer present) or AUTO (scheduler)
    ADD CONSTRAINT chk_sub_payment_source CHECK (source IN ('CHECKOUT', 'AUTO'));

-- One Paystack transaction id can settle only one payment row.
CREATE UNIQUE INDEX uq_sub_payments_paystack_tx ON subscription_payments(paystack_transaction) WHERE paystack_transaction IS NOT NULL;

-- The reusable authorization (never raw card data). The code is AES-GCM encrypted with APP_ENCRYPTION_KEY and is never returned by any API.
CREATE TABLE billing_authorizations (
    id                      uuid PRIMARY KEY,
    business_id             uuid         NOT NULL REFERENCES businesses(id),
    customer_email          varchar(254) NOT NULL,
    authorization_code_enc  text         NOT NULL,
    signature               varchar(120),
    channel                 varchar(20),
    card_type               varchar(30),
    bank                    varchar(80),
    last4                   varchar(4),
    exp_month               varchar(2),
    exp_year                varchar(4),
    reusable                boolean      NOT NULL,
    active                  boolean      NOT NULL DEFAULT true,
    source_reference        varchar(80),
    consented_at            timestamptz  NOT NULL,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_billing_auth_active ON billing_authorizations(business_id) WHERE active;

-- One row per renewal attempt. UNIQUE(subscription, period being renewed, attempt number) is the database-backed idempotency:
-- however many instances run the scheduler, an attempt is created, and therefore charged, at most once.
CREATE TABLE subscription_renewals (
    id               uuid PRIMARY KEY,
    subscription_id  uuid          NOT NULL REFERENCES subscriptions(id),
    business_id      uuid          NOT NULL REFERENCES businesses(id),
    due_at           timestamptz   NOT NULL,          -- the period end being renewed
    attempt_no       int           NOT NULL CHECK (attempt_no >= 1),
    status           varchar(12)   NOT NULL DEFAULT 'PENDING',   -- PENDING, SUCCESS, FAILED
    payment_id       uuid REFERENCES subscription_payments(id),
    failure_reason   varchar(300),
    next_retry_at    timestamptz,                      -- null when no further attempt will be made
    created_at       timestamptz   NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    CONSTRAINT chk_renewal_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT uq_renewal_attempt UNIQUE (subscription_id, due_at, attempt_no)
);
CREATE INDEX idx_renewals_business ON subscription_renewals(business_id, created_at DESC);
CREATE INDEX idx_subscriptions_due ON subscriptions(current_period_end) WHERE auto_renew;
