-- Real-PostgreSQL checks of the invoice tables after V1..V28. Run against a scratch database:
--   psql -d scratch -v ON_ERROR_STOP=1 -f scripts/db-checks/invoice_db_check.sql
-- Each check raises an exception on failure. Everything runs in a transaction that is rolled back.
BEGIN;
CREATE TEMP TABLE _r(name text, ok boolean);
INSERT INTO businesses (id, name, slug, business_type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Biz A','biz-a-chk','RETAIL'), ('bbbbbbbb-0000-0000-0000-000000000002','Biz B','biz-b-chk','RETAIL');

-- 1. numbering is gap-free and separate per business (the exact upsert CounterService runs)
DO $$ DECLARE a1 bigint; a2 bigint; a3 bigint; b1 bigint; BEGIN
  EXECUTE $q$INSERT INTO business_counters (business_id, name, value) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','invoice',1) ON CONFLICT (business_id, name) DO UPDATE SET value = business_counters.value + 1 RETURNING value$q$ INTO a1;
  EXECUTE $q$INSERT INTO business_counters (business_id, name, value) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','invoice',1) ON CONFLICT (business_id, name) DO UPDATE SET value = business_counters.value + 1 RETURNING value$q$ INTO a2;
  EXECUTE $q$INSERT INTO business_counters (business_id, name, value) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','invoice',1) ON CONFLICT (business_id, name) DO UPDATE SET value = business_counters.value + 1 RETURNING value$q$ INTO a3;
  EXECUTE $q$INSERT INTO business_counters (business_id, name, value) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','invoice',1) ON CONFLICT (business_id, name) DO UPDATE SET value = business_counters.value + 1 RETURNING value$q$ INTO b1;
  IF (a1,a2,a3,b1) <> (1,2,3,1) THEN RAISE EXCEPTION 'numbering wrong: %,%,%,%', a1,a2,a3,b1; END IF;
  INSERT INTO _r VALUES ('invoice numbers 1,2,3 for A and 1 for B', true);
END $$;

-- 2. an invoice and its items save together; same request id twice is refused by the database, other business may reuse it
INSERT INTO invoices (id, business_id, invoice_number, customer_name, status, issue_date, subtotal, total, share_token, client_request_id)
VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',1,'Ada','DRAFT',current_date,10000,10750,'tok-a-1','req-1');
INSERT INTO invoice_items (invoice_id, business_id, position, description, quantity, unit_price, line_total, tax_rate, tax_amount)
VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',0,'Rice 50kg',2,5000,10000,7.5,750);
DO $$ BEGIN
  BEGIN
    INSERT INTO invoices (business_id, invoice_number, customer_name, issue_date, subtotal, total, share_token, client_request_id)
    VALUES ('aaaaaaaa-0000-0000-0000-000000000001',2,'Ada',current_date,1,1,'tok-a-2','req-1');
    RAISE EXCEPTION 'duplicate request id was accepted';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  INSERT INTO invoices (business_id, invoice_number, customer_name, issue_date, subtotal, total, share_token, client_request_id)
  VALUES ('bbbbbbbb-0000-0000-0000-000000000002',1,'Chi',current_date,1,1,'tok-b-1','req-1');
  INSERT INTO invoices (business_id, invoice_number, customer_name, issue_date, subtotal, total, share_token)
  VALUES ('aaaaaaaa-0000-0000-0000-000000000001',3,'X',current_date,1,1,'tok-a-3'), ('aaaaaaaa-0000-0000-0000-000000000001',4,'Y',current_date,1,1,'tok-a-4');  -- several with no request id are fine
  INSERT INTO _r VALUES ('duplicate request id refused within a business, allowed across businesses, NULLs unlimited', true);
END $$;

-- 3. the database refuses impossible money states
DO $$ BEGIN
  BEGIN UPDATE invoices SET amount_paid = 99999 WHERE id='11111111-0000-0000-0000-000000000001'; RAISE EXCEPTION 'overpaid invoice accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN INSERT INTO invoices (business_id, invoice_number, customer_name, status, issue_date, subtotal, total, share_token) VALUES ('aaaaaaaa-0000-0000-0000-000000000001',9,'Z','OVERDUE',current_date,1,1,'tok-z');
    RAISE EXCEPTION 'OVERDUE stored'; EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN INSERT INTO invoices (business_id, invoice_number, customer_name, issue_date, subtotal, total, share_token) VALUES ('aaaaaaaa-0000-0000-0000-000000000001',1,'dup number',current_date,1,1,'tok-dupn');
    RAISE EXCEPTION 'duplicate number accepted'; EXCEPTION WHEN unique_violation THEN NULL; END;
  INSERT INTO _r VALUES ('overpayment, stored OVERDUE and duplicate invoice numbers are refused', true);
END $$;

-- 4. a payment posts to the ledger with the kind the service uses
INSERT INTO invoice_payments (invoice_id, business_id, amount, method, client_request_id) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',5000,'CASH','pay-1');
DO $$ BEGIN
  BEGIN INSERT INTO invoice_payments (invoice_id, business_id, amount, method, client_request_id) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',5000,'CASH','pay-1');
    RAISE EXCEPTION 'duplicate payment accepted'; EXCEPTION WHEN unique_violation THEN NULL; END;
  INSERT INTO _r VALUES ('a retried payment request id cannot be recorded twice', true);
END $$;

-- 5. tenant isolation: the lookup the service uses (id AND business_id) finds nothing for another business
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM invoices WHERE id='11111111-0000-0000-0000-000000000001' AND business_id='bbbbbbbb-0000-0000-0000-000000000002';
  IF n <> 0 THEN RAISE EXCEPTION 'other business can read the invoice'; END IF;
  SELECT count(*) INTO n FROM invoices WHERE id='11111111-0000-0000-0000-000000000001' AND business_id='aaaaaaaa-0000-0000-0000-000000000001';
  IF n <> 1 THEN RAISE EXCEPTION 'owner cannot read the invoice'; END IF;
  INSERT INTO _r VALUES ('invoice is visible to its business only', true);
END $$;

SELECT * FROM _r;
ROLLBACK;
