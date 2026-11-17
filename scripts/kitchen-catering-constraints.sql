-- Database-level checks for the Kitchen and Catering guards. Run against a scratch database with all migrations applied:
--   psql -d scratch -v ON_ERROR_STOP=1 -f scripts/kitchen-catering-constraints.sql
-- Everything runs in one transaction that is rolled back, so nothing is kept.
BEGIN;
DO $t$
DECLARE b1 uuid := gen_random_uuid(); b2 uuid := gen_random_uuid(); br1 uuid := gen_random_uuid(); ev uuid := gen_random_uuid(); n int; ok boolean;
BEGIN
  INSERT INTO businesses(id,name,slug,business_type) VALUES (b1,'A','kc-a-'||left(b1::text,8),'CATERING'),(b2,'B','kc-b-'||left(b2::text,8),'CATERING');
  INSERT INTO branches(id,business_id,name) VALUES (br1,b1,'Main');

  INSERT INTO catering_events(id,business_id,branch_id,number,title,customer_name,event_date,guests,total)
    VALUES (ev,b1,br1,1,'Wedding','Mrs Ade',current_date+10,100,500000);
  RAISE NOTICE 'PASS event created';

  -- status and numeric constraints
  BEGIN UPDATE catering_events SET status='BOGUS' WHERE id=ev; RAISE EXCEPTION 'bad status accepted'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS invalid status rejected'; END;
  BEGIN UPDATE catering_events SET guests=0 WHERE id=ev; RAISE EXCEPTION 'zero guests accepted'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS zero guests rejected'; END;
  BEGIN UPDATE catering_events SET total=-1 WHERE id=ev; RAISE EXCEPTION 'negative total accepted'; EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS negative total rejected'; END;
  BEGIN INSERT INTO catering_events(id,business_id,branch_id,number,title,customer_name,event_date,guests) VALUES (gen_random_uuid(),b1,br1,1,'Dup','X',current_date,1);
        RAISE EXCEPTION 'duplicate number accepted'; EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'PASS duplicate event number per business rejected'; END;

  -- duplicate payment request (double click / retry) is blocked, a different key is allowed, no key is allowed
  INSERT INTO catering_payments(id,business_id,event_id,kind,method,amount,client_request_id) VALUES (gen_random_uuid(),b1,ev,'DEPOSIT','CASH',200000,'req-1');
  BEGIN INSERT INTO catering_payments(id,business_id,event_id,kind,method,amount,client_request_id) VALUES (gen_random_uuid(),b1,ev,'DEPOSIT','CASH',200000,'req-1');
        RAISE EXCEPTION 'duplicate payment accepted'; EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'PASS duplicate payment request rejected'; END;
  INSERT INTO catering_payments(id,business_id,event_id,kind,method,amount,client_request_id) VALUES (gen_random_uuid(),b1,ev,'PAYMENT','CASH',50000,'req-2');
  BEGIN INSERT INTO catering_payments(id,business_id,event_id,kind,method,amount) VALUES (gen_random_uuid(),b1,ev,'PAYMENT','CASH',0); RAISE EXCEPTION 'zero payment accepted';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS zero payment rejected'; END;
  BEGIN INSERT INTO catering_payments(id,business_id,event_id,kind,method,amount) VALUES (gen_random_uuid(),b1,ev,'GIFT','CASH',5); RAISE EXCEPTION 'bad kind accepted';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'PASS invalid payment kind rejected'; END;

  -- stock may be issued once: the guarded update changes a row the first time and none afterwards
  UPDATE catering_events SET stock_issued_at=now() WHERE id=ev AND stock_issued_at IS NULL; GET DIAGNOSTICS n = ROW_COUNT;
  IF n<>1 THEN RAISE EXCEPTION 'first issue should claim the event'; END IF;
  UPDATE catering_events SET stock_issued_at=now() WHERE id=ev AND stock_issued_at IS NULL; GET DIAGNOSTICS n = ROW_COUNT;
  IF n<>0 THEN RAISE EXCEPTION 'second issue must not claim the event'; END IF;
  RAISE NOTICE 'PASS stock issue guard fires once';

  -- one package / service name per business, case-insensitive, but another business may reuse it
  INSERT INTO catering_packages(id,business_id,name) VALUES (gen_random_uuid(),b1,'Gold');
  BEGIN INSERT INTO catering_packages(id,business_id,name) VALUES (gen_random_uuid(),b1,'gold'); RAISE EXCEPTION 'duplicate package accepted';
  EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'PASS duplicate package name rejected'; END;
  INSERT INTO catering_packages(id,business_id,name) VALUES (gen_random_uuid(),b2,'Gold');
  RAISE NOTICE 'PASS another business can reuse a package name';

  -- deleting the event removes its dependants, never leaves orphans
  DELETE FROM catering_events WHERE id=ev;
  SELECT count(*) INTO n FROM catering_payments WHERE event_id=ev;
  IF n<>0 THEN RAISE EXCEPTION 'orphan payments'; END IF;
  RAISE NOTICE 'PASS dependants cascade with the event';
END $t$;
ROLLBACK;
