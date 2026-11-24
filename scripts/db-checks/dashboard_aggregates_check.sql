-- Dashboard aggregate regression, on real PostgreSQL after V1..V28. Rolled back at the end; nothing is kept.
--   psql -d scratch -v ON_ERROR_STOP=1 -f scripts/db-checks/dashboard_aggregates_check.sql
-- Each scenario runs the same aggregates the dashboard uses (as SQL) for today, yesterday, thisWeek and thisMonth
-- (Africa/Lagos) and checks the combined figure the service builds: zero when empty, the real total otherwise, no cross-business leakage.
BEGIN;
CREATE TEMP TABLE _r(name text, ok boolean);
INSERT INTO businesses (id, name, slug, business_type) VALUES
 ('d0000000-0000-0000-0000-00000000000e','Empty','dash-empty','RETAIL'),
 ('d0000000-0000-0000-0000-00000000005a','SalesOnly','dash-sales','RETAIL'),
 ('d0000000-0000-0000-0000-0000000000f1','Full','dash-full','RETAIL');
INSERT INTO customers (id, business_id, name) VALUES ('c0000000-0000-0000-0000-0000000000f1','d0000000-0000-0000-0000-0000000000f1','Ada');
-- SalesOnly: one cash sale of 5000 today, no customer payments, no expenses, no invoices
INSERT INTO sales (id, business_id, sale_number, status, subtotal, total, amount_paid, amount_owed, cost_total) VALUES ('50000000-0000-0000-0000-00000000005a','d0000000-0000-0000-0000-00000000005a',1,'COMPLETED',5000,5000,5000,0,3000);
INSERT INTO sale_payments (id, sale_id, business_id, method, amount) VALUES (gen_random_uuid(),'50000000-0000-0000-0000-00000000005a','d0000000-0000-0000-0000-00000000005a','CASH',5000);
-- Full: sale 8000, customer repayment 2000, expense 1500, standalone invoice 10000 with a 4000 payment
INSERT INTO sales (id, business_id, sale_number, status, subtotal, total, amount_paid, amount_owed, cost_total) VALUES ('50000000-0000-0000-0000-0000000000f1','d0000000-0000-0000-0000-0000000000f1',1,'COMPLETED',8000,8000,8000,0,4000);
INSERT INTO sale_payments (id, sale_id, business_id, method, amount) VALUES (gen_random_uuid(),'50000000-0000-0000-0000-0000000000f1','d0000000-0000-0000-0000-0000000000f1','TRANSFER',8000);
INSERT INTO customer_payments (id, business_id, customer_id, amount, method) VALUES (gen_random_uuid(),'d0000000-0000-0000-0000-0000000000f1','c0000000-0000-0000-0000-0000000000f1',2000,'CASH');
INSERT INTO expenses (id, business_id, category, amount, expense_date, payment_method) VALUES (gen_random_uuid(),'d0000000-0000-0000-0000-0000000000f1','Transport',1500,(now() at time zone 'Africa/Lagos')::date,'CASH');
INSERT INTO invoices (id, business_id, invoice_number, customer_name, status, issue_date, subtotal, total, amount_paid, share_token) VALUES ('10000000-0000-0000-0000-0000000000f1','d0000000-0000-0000-0000-0000000000f1',1,'Ada','PARTIALLY_PAID',current_date,10000,10000,4000,'dash-tok');
INSERT INTO invoice_payments (invoice_id, business_id, amount, method) VALUES ('10000000-0000-0000-0000-0000000000f1','d0000000-0000-0000-0000-0000000000f1',4000,'CASH');

-- the figures the service builds, for one business and one period
CREATE FUNCTION _dash(b uuid, f timestamptz, t timestamptz, d1 date, d2 date, OUT raw_cp numeric, OUT repayments numeric, OUT sales_total numeric, OUT expenses_total numeric, OUT fromsales numeric) AS $$
BEGIN
  SELECT sum(amount) INTO raw_cp FROM customer_payments WHERE business_id=b AND created_at>=f AND created_at<t;          -- the raw query: NULL when empty
  repayments := coalesce(raw_cp,0) + coalesce((SELECT sum(p.amount) FROM invoice_payments p JOIN invoices i ON i.id=p.invoice_id
                  WHERE i.business_id=b AND p.business_id=b AND i.source_sale_id IS NULL AND i.source_document_id IS NULL AND p.created_at>=f AND p.created_at<t),0);
  SELECT coalesce(sum(total-refunded_amount),0) INTO sales_total FROM sales WHERE business_id=b AND status='COMPLETED' AND created_at>=f AND created_at<t;
  SELECT coalesce(sum(amount),0) INTO expenses_total FROM expenses WHERE business_id=b AND status='APPROVED' AND expense_date>=d1 AND expense_date<=d2;
  SELECT coalesce(sum(sp.amount),0) INTO fromsales FROM sale_payments sp JOIN sales s ON s.id=sp.sale_id WHERE s.business_id=b AND s.status='COMPLETED' AND s.created_at>=f AND s.created_at<t;
END $$ LANGUAGE plpgsql;

DO $$
DECLARE
  tz text := 'Africa/Lagos';
  d date := (now() at time zone tz)::date;
  ranges text[][] := ARRAY[['today',  d::text, d::text],
                           ['yesterday', (d-1)::text, (d-1)::text],
                           ['thisWeek', (d - ((extract(isodow from d)::int) - 1))::text, d::text],
                           ['thisMonth', date_trunc('month', d)::date::text, d::text]];
  i int; nm text; a date; z date; f timestamptz; t timestamptz; r record; biz uuid;
  e uuid := 'd0000000-0000-0000-0000-00000000000e'; s uuid := 'd0000000-0000-0000-0000-00000000005a'; fu uuid := 'd0000000-0000-0000-0000-0000000000f1';
BEGIN
  FOR i IN 1..4 LOOP
    nm := ranges[i][1]; a := ranges[i][2]::date; z := ranges[i][3]::date;
    f := (a::timestamp) AT TIME ZONE tz; t := ((z+1)::timestamp) AT TIME ZONE tz;
    -- empty business: raw customer-payment sum IS null (the defect), the dashboard figures are all zero
    SELECT * INTO r FROM _dash(e,f,t,a,z);
    IF r.raw_cp IS NOT NULL THEN RAISE EXCEPTION '% empty: expected the raw sum to be NULL', nm; END IF;
    IF (r.repayments, r.sales_total, r.expenses_total, r.fromsales) IS DISTINCT FROM (0::numeric,0::numeric,0::numeric,0::numeric) THEN RAISE EXCEPTION '% empty: not zero: %', nm, r; END IF;
    -- sales but no customer payments
    SELECT * INTO r FROM _dash(s,f,t,a,z);
    IF r.raw_cp IS NOT NULL OR r.repayments <> 0 THEN RAISE EXCEPTION '% sales-only: repayments should be 0, got %', nm, r.repayments; END IF;
    IF nm IN ('today','thisWeek','thisMonth') THEN
      IF r.sales_total <> 5000 OR r.fromsales <> 5000 THEN RAISE EXCEPTION '% sales-only: wrong totals %', nm, r; END IF;
    ELSE
      IF r.sales_total <> 0 OR r.fromsales <> 0 THEN RAISE EXCEPTION 'yesterday sales-only should be 0, got %', r; END IF;
    END IF;
    -- full records: repayments = 2000 customer + 4000 invoice payment; other business's 5000 sale never appears
    SELECT * INTO r FROM _dash(fu,f,t,a,z);
    IF nm = 'yesterday' THEN
      IF (r.repayments, r.sales_total, r.expenses_total, r.fromsales) IS DISTINCT FROM (0::numeric,0::numeric,0::numeric,0::numeric) THEN RAISE EXCEPTION 'yesterday full: should be zero, got %', r; END IF;
    ELSE
      IF r.repayments <> 6000 OR r.sales_total <> 8000 OR r.expenses_total <> 1500 OR r.fromsales <> 8000 THEN RAISE EXCEPTION '% full: wrong totals %', nm, r; END IF;
    END IF;
    INSERT INTO _r VALUES ('range '||nm||': empty=0, sales-only (no repayments)=ok, full=actual totals, no leakage between businesses', true);
  END LOOP;
END $$;
SELECT * FROM _r;
ROLLBACK;
