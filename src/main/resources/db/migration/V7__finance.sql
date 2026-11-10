-- V7: cash and bank accounts, a money ledger, expense approvals and cashier shifts.
-- Existing money records are carried into the ledger so balances reflect what has already happened.

CREATE TABLE money_accounts (
  id              uuid PRIMARY KEY,
  business_id     uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name            varchar(100) NOT NULL,
  kind            varchar(15) NOT NULL,                 -- CASH, BANK, MOBILE_MONEY
  opening_balance numeric(14,2) NOT NULL DEFAULT 0,
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_money_account_name ON money_accounts(business_id, lower(name));
CREATE INDEX idx_money_accounts_business ON money_accounts(business_id);

INSERT INTO money_accounts (id, business_id, name, kind) SELECT gen_random_uuid(), id, 'Cash on hand', 'CASH' FROM businesses;
INSERT INTO money_accounts (id, business_id, name, kind) SELECT gen_random_uuid(), id, 'Bank account', 'BANK' FROM businesses;

CREATE TABLE money_movements (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  account_id  uuid NOT NULL REFERENCES money_accounts(id),
  branch_id   uuid REFERENCES branches(id),
  direction   varchar(3) NOT NULL CHECK (direction IN ('IN','OUT')),
  amount      numeric(14,2) NOT NULL CHECK (amount > 0),
  method      varchar(20),
  kind        varchar(30) NOT NULL,   -- SALE, CUSTOMER_PAYMENT, REFUND, EXPENSE, EXPENSE_REVERSAL, SUPPLIER_PAYMENT, PURCHASE, DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, CATERING_DEPOSIT, ONLINE_ORDER
  ref_type    varchar(30),
  ref_id      uuid,
  note        varchar(300),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_money_mv_account_time ON money_movements(account_id, created_at);
CREATE INDEX idx_money_mv_business_time ON money_movements(business_id, created_at);
CREATE INDEX idx_money_mv_ref ON money_movements(ref_type, ref_id);

-- Backfill history (CASH -> cash account, everything else -> bank account).
INSERT INTO money_movements (id, business_id, account_id, branch_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), sp.business_id, a.id, s.branch_id, 'IN', sp.amount, sp.method, 'SALE', 'SALE', s.id, s.created_by, s.created_at
FROM sale_payments sp JOIN sales s ON s.id = sp.sale_id
JOIN money_accounts a ON a.business_id = sp.business_id AND a.kind = CASE WHEN sp.method = 'CASH' THEN 'CASH' ELSE 'BANK' END;

INSERT INTO money_movements (id, business_id, account_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), cp.business_id, a.id, 'IN', cp.amount, cp.method, 'CUSTOMER_PAYMENT', 'CUSTOMER', cp.customer_id, cp.created_by, cp.created_at
FROM customer_payments cp JOIN money_accounts a ON a.business_id = cp.business_id AND a.kind = CASE WHEN cp.method = 'CASH' THEN 'CASH' ELSE 'BANK' END;

INSERT INTO money_movements (id, business_id, account_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), e.business_id, a.id, 'OUT', e.amount, e.payment_method, 'EXPENSE', 'EXPENSE', e.id, e.created_by, e.created_at
FROM expenses e JOIN money_accounts a ON a.business_id = e.business_id AND a.kind = CASE WHEN e.payment_method = 'CASH' THEN 'CASH' ELSE 'BANK' END;

INSERT INTO money_movements (id, business_id, account_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), sp.business_id, a.id, 'OUT', sp.amount, sp.method, 'SUPPLIER_PAYMENT', 'SUPPLIER', sp.supplier_id, sp.created_by, sp.created_at
FROM supplier_payments sp JOIN money_accounts a ON a.business_id = sp.business_id AND a.kind = CASE WHEN sp.method = 'CASH' THEN 'CASH' ELSE 'BANK' END;

-- Amounts paid at purchase time had no recorded method; they are treated as cash.
INSERT INTO money_movements (id, business_id, account_id, branch_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), p.business_id, a.id, p.branch_id, 'OUT', p.amount_paid, 'CASH', 'PURCHASE', 'PURCHASE', p.id, p.created_by, p.created_at
FROM purchases p JOIN money_accounts a ON a.business_id = p.business_id AND a.kind = 'CASH' WHERE p.amount_paid > 0;

-- Refunded sales: the money that was received is treated as paid back.
INSERT INTO money_movements (id, business_id, account_id, branch_id, direction, amount, method, kind, ref_type, ref_id, created_by, created_at)
SELECT gen_random_uuid(), sp.business_id, a.id, s.branch_id, 'OUT', sp.amount, sp.method, 'REFUND', 'SALE', s.id, s.created_by, s.refunded_at
FROM sale_payments sp JOIN sales s ON s.id = sp.sale_id AND s.refunded_at IS NOT NULL
JOIN money_accounts a ON a.business_id = sp.business_id AND a.kind = CASE WHEN sp.method = 'CASH' THEN 'CASH' ELSE 'BANK' END;

ALTER TABLE purchases ADD COLUMN payment_method varchar(20);

-- Expense approvals
ALTER TABLE businesses ADD COLUMN expense_approval_threshold numeric(14,2);   -- null = approvals off
ALTER TABLE expenses ADD COLUMN status varchar(10) NOT NULL DEFAULT 'APPROVED';  -- PENDING, APPROVED, REJECTED
ALTER TABLE expenses ADD COLUMN decided_by uuid;
ALTER TABLE expenses ADD COLUMN decided_at timestamptz;
ALTER TABLE expenses ADD COLUMN decision_note varchar(300);
ALTER TABLE expenses ADD COLUMN branch_id uuid REFERENCES branches(id);

-- Cashier shifts and reconciliation
CREATE TABLE cashier_shifts (
  id            uuid PRIMARY KEY,
  business_id   uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id     uuid REFERENCES branches(id),
  user_id       uuid NOT NULL,
  opening_float numeric(14,2) NOT NULL DEFAULT 0,
  opened_at     timestamptz NOT NULL DEFAULT now(),
  closed_at     timestamptz,
  expected_cash numeric(14,2),
  counted_cash  numeric(14,2),
  variance      numeric(14,2),
  note          varchar(300),
  status        varchar(10) NOT NULL DEFAULT 'OPEN',   -- OPEN, CLOSED
  reviewed_by   uuid,
  reviewed_at   timestamptz,
  review_note   varchar(300)
);
CREATE UNIQUE INDEX uq_open_shift_per_user ON cashier_shifts(business_id, user_id) WHERE status = 'OPEN';
CREATE INDEX idx_shifts_business_time ON cashier_shifts(business_id, opened_at DESC);
