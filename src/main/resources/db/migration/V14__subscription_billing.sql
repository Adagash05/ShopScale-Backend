-- V14: ShopScale subscription billing, charged to the AMSATI Paystack account (not merchants' accounts).
-- A subscription is activated ONLY from a row here that Paystack has confirmed server-side (verify call or signed webhook).
CREATE TABLE subscription_payments (
    id                   uuid PRIMARY KEY,
    business_id          uuid          NOT NULL REFERENCES businesses(id),
    plan_code            varchar(30)   NOT NULL REFERENCES plans(code),
    billing_cycle        varchar(20)   NOT NULL,
    kind                 varchar(20)   NOT NULL,                 -- NEW, RENEWAL, CHANGE
    amount               numeric(14,2) NOT NULL,
    list_price           numeric(14,2) NOT NULL,
    credit               numeric(14,2) NOT NULL DEFAULT 0,
    currency             varchar(3)    NOT NULL DEFAULT 'NGN',
    reference            varchar(80)   NOT NULL UNIQUE,
    status               varchar(20)   NOT NULL DEFAULT 'PENDING', -- PENDING, SUCCESS, FAILED, MISMATCH
    initiated_by         uuid,
    paystack_transaction varchar(40),
    paid_at              timestamptz,
    period_start         timestamptz,
    period_end           timestamptz,
    failure_note         varchar(300),
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_sub_payments_business ON subscription_payments(business_id, created_at DESC);
