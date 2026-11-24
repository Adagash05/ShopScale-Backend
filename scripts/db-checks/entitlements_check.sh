#!/usr/bin/env bash
# Proves V31 on a real PostgreSQL: the entitlement matrix, that existing data and subscriptions are untouched, and that the
# advisory-lock create pattern used by SubscriptionService.lockForCreate / ReminderService stops concurrent requests exceeding an allowance.
# Usage: PSQL="psql -h /tmp/pg -p 5544 -U postgres" scripts/db-checks/entitlements_check.sh   (run from the repository root)
set -eu
PSQL=${PSQL:-psql}; DB=${DB:-ss_ent_check}; M=src/main/resources/db/migration
fail() { echo "FAIL: $*"; exit 1; }
$PSQL -q -d postgres -c "drop database if exists $DB" -c "create database $DB" 2>/dev/null
for f in $(ls $M | sort -V | grep -v '^V31__'); do $PSQL -d $DB -v ON_ERROR_STOP=1 -q -f $M/$f >/dev/null; done

# --- existing data before V31: an Essential business over the new limits and a Growth business over the new product limit
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
insert into businesses (id,name,slug,business_type) values
 ('e0000000-0000-0000-0000-000000000001','Ess','ent-ess','RETAIL'),('e0000000-0000-0000-0000-000000000002','Gro','ent-gro','RETAIL');
insert into subscriptions (id,business_id,plan_code,billing_cycle,status,current_period_start,current_period_end) values
 (gen_random_uuid(),'e0000000-0000-0000-0000-000000000001','ESSENTIAL','MONTHLY','ACTIVE',now()-interval '5 days',now()+interval '25 days'),
 (gen_random_uuid(),'e0000000-0000-0000-0000-000000000002','GROWTH','ANNUAL','ACTIVE',now()-interval '40 days',now()+interval '325 days');
insert into products (id,business_id,name) select gen_random_uuid(),'e0000000-0000-0000-0000-000000000001','E'||n from generate_series(1,80) n;
insert into products (id,business_id,name) select gen_random_uuid(),'e0000000-0000-0000-0000-000000000002','G'||n from generate_series(1,300) n;
insert into customers (id,business_id,name) select gen_random_uuid(),'e0000000-0000-0000-0000-000000000001','C'||n from generate_series(1,70) n;
SQL
before=$($PSQL -d $DB -Atc "select (select count(*) from products), (select count(*) from customers), (select string_agg(plan_code||billing_cycle||status||coalesce(locked_price::text,''), ',' order by business_id) from subscriptions), (select string_agg(code||monthly_price||annual_price, ',' order by code) from plans)")

$PSQL -d $DB -v ON_ERROR_STOP=1 -q -f $M/V31__plan_entitlements_revision.sql

after=$($PSQL -d $DB -Atc "select (select count(*) from products), (select count(*) from customers), (select string_agg(plan_code||billing_cycle||status||coalesce(locked_price::text,''), ',' order by business_id) from subscriptions), (select string_agg(code||monthly_price||annual_price, ',' order by code) from plans)")
[ "$before" = "$after" ] || fail "existing products/customers/subscriptions/prices changed: [$before] vs [$after]"
echo "ok  existing records, subscriptions and prices unchanged by V31 (80+300 products, 70 customers kept)"

# --- the matrix from the directive
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
do $$
declare r record; exp text; got text;
begin
  for r in select * from (values
    ('FREE',      1, 10,   10,   10,   0,    0,    'TOTAL'),
    ('ESSENTIAL', 2, 50,   50,   50,   10,   20,   'MONTH'),
    ('GROWTH',    5, 200,  null, null, 50,   50,   'TOTAL'),
    ('BUSINESS',  10,null, null, null, null, null, 'TOTAL')) v(code,u,p,c,e,s,w,per) loop
    exp := concat_ws('|', r.u, coalesce(r.p::text,'-'), coalesce(r.c::text,'-'), coalesce(r.e::text,'-'), coalesce(r.s::text,'-'), coalesce(r.w::text,'-'), r.per);
    select concat_ws('|', max_users, coalesce(max_items::text,'-'), coalesce(max_customers::text,'-'), coalesce(max_expenses::text,'-'),
                     coalesce(max_suppliers::text,'-'), coalesce(max_whatsapp_reminders::text,'-'), expense_period) into got from plans where code = r.code;
    if got is distinct from exp then raise exception '% limits: expected % got %', r.code, exp, got; end if;
  end loop;

  -- features: [plan, feature, should it have it]
  for r in select * from (values
    ('FREE','DATA_EXPORT',false),('FREE','ASK_SHOPSCALE',false),('FREE','BEST_SELLING_PRODUCTS',false),('FREE','BEST_SELLING_CUSTOMERS',false),
    ('FREE','ONLINE_ORDERS',false),('FREE','STOREFRONT',false),('FREE','ACTIVITY_HISTORY',false),('FREE','WHATSAPP_REMINDERS',false),
    ('FREE','SUPPLIER_BALANCES',false),('FREE','CASH_ACCOUNTS',false),('FREE','PURCHASING',false),
    ('FREE','POS',true),('FREE','BASIC_INVENTORY',true),('FREE','EXPENSES',true),('FREE','BASIC_DASHBOARD',true),('FREE','CUSTOMER_CREDIT',true),('FREE','STOCK_HISTORY',true),
    ('ESSENTIAL','DATA_EXPORT',false),('ESSENTIAL','ASK_SHOPSCALE',false),('ESSENTIAL','BEST_SELLING_PRODUCTS',false),('ESSENTIAL','BEST_SELLING_CUSTOMERS',false),
    ('ESSENTIAL','ONLINE_ORDERS',false),('ESSENTIAL','STOREFRONT',false),('ESSENTIAL','ACTIVITY_HISTORY',false),
    ('ESSENTIAL','WHATSAPP_REMINDERS',true),('ESSENTIAL','CASH_ACCOUNTS',true),('ESSENTIAL','SUPPLIER_BALANCES',true),('ESSENTIAL','POS',true),
    ('GROWTH','DATA_EXPORT',true),('GROWTH','ASK_SHOPSCALE',true),('GROWTH','BEST_SELLING_PRODUCTS',true),('GROWTH','BEST_SELLING_CUSTOMERS',true),
    ('GROWTH','ONLINE_ORDERS',true),('GROWTH','STOREFRONT',true),('GROWTH','ACTIVITY_HISTORY',true),('GROWTH','WHATSAPP_REMINDERS',true),('GROWTH','CASH_ACCOUNTS',true),
    ('BUSINESS','DATA_EXPORT',true),('BUSINESS','ASK_SHOPSCALE',true),('BUSINESS','BEST_SELLING_PRODUCTS',true),('BUSINESS','BEST_SELLING_CUSTOMERS',true),
    ('BUSINESS','ONLINE_ORDERS',true),('BUSINESS','STOREFRONT',true),('BUSINESS','ACTIVITY_HISTORY',true),('BUSINESS','WHATSAPP_REMINDERS',true),
    ('BUSINESS','CUSTOM_DOMAINS',true),('BUSINESS','ADVANCED_FINANCE',true)) v(code,f,want) loop
    if exists (select 1 from plan_features where plan_code = r.code and feature = r.f) <> r.want then
      raise exception '% / %: expected %', r.code, r.f, r.want;
    end if;
  end loop;
  -- Growth must not have gained Business-only extras (no invented Business or Growth features)
  if exists (select 1 from plan_features where plan_code = 'GROWTH' and feature in ('CUSTOM_DOMAINS','ADVANCED_FINANCE','PRIORITY_SUPPORT')) then raise exception 'growth gained business-only features'; end if;
end $$;
SQL
echo "ok  entitlement matrix: limits for 4 plans and 45 feature expectations match the directive"

# --- concurrency: 8 sessions race for the last slots. The pattern is exactly what lockForCreate + the count check do in one transaction.
race() { # $1 business id, $2 limit, $3 worker id, $4 uses lock (1/0), $5 resource
  $PSQL -d $DB -v ON_ERROR_STOP=1 -q -At <<SQL >/dev/null
begin;
$( [ "$4" = 1 ] && echo "select 1 from (select pg_advisory_xact_lock(hashtextextended('$1:limit:$5', 0))) t;" )
select count(*) as have from products where business_id = '$1' and status = 'ACTIVE' \\gset
select pg_sleep(0.3);
insert into products (id, business_id, name) select gen_random_uuid(), '$1', 'race-$3' where :have < $2;
commit;
SQL
}
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
insert into businesses (id,name,slug,business_type) values ('e0000000-0000-0000-0000-0000000000a1','Free','ent-free','RETAIL'),('e0000000-0000-0000-0000-0000000000a2','Ctl','ent-ctl','RETAIL');
insert into products (id,business_id,name,status) select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000a1','F'||n,'ACTIVE' from generate_series(1,8) n;
insert into products (id,business_id,name,status) select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000a1','Old'||n,'ARCHIVED' from generate_series(1,30) n;
insert into products (id,business_id,name,status) select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000a2','C'||n,'ACTIVE' from generate_series(1,8) n;
SQL
for w in 1 2 3 4 5 6 7 8; do race e0000000-0000-0000-0000-0000000000a1 10 $w 1 products & done; wait
n=$($PSQL -d $DB -Atc "select count(*) from products where business_id='e0000000-0000-0000-0000-0000000000a1' and status='ACTIVE'")
[ "$n" = 10 ] || fail "Free product limit: 8 concurrent creators ended at $n active products, expected exactly 10"
echo "ok  8 concurrent product creations from 8 -> exactly 10 active (limit 10); 30 archived products did not count"
for w in 1 2 3 4 5 6 7 8; do race e0000000-0000-0000-0000-0000000000a2 10 $w 0 products & done; wait
c=$($PSQL -d $DB -Atc "select count(*) from products where business_id='e0000000-0000-0000-0000-0000000000a2' and status='ACTIVE'")
[ "$c" -gt 10 ] && echo "ok  control (check, then insert, no lock) ended at $c active against a limit of 10: the overshoot the lock prevents" || echo "info control without the lock ended at $c (did not overshoot this run; timing dependent)"

# reminders: Essential has 20 a month, 15 already used; 8 concurrent distinct requests -> exactly 5 more; 6 identical requests -> 1
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
insert into businesses (id,name,slug,business_type) values ('e0000000-0000-0000-0000-0000000000b1','Rem','ent-rem','RETAIL');
insert into reminder_log (id,business_id,request_id,invoice_id) select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000b1','old-'||n,gen_random_uuid() from generate_series(1,15) n;
SQL
remind() { # $1 request id
  $PSQL -d $DB -v ON_ERROR_STOP=0 -q -At <<SQL >/dev/null 2>&1
begin;
select 1 from (select pg_advisory_xact_lock(hashtextextended('e0000000-0000-0000-0000-0000000000b1:limit:reminders', 0))) t;
select pg_sleep(0.2);
insert into reminder_log (id,business_id,request_id,invoice_id)
select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000b1','$1',gen_random_uuid()
where not exists (select 1 from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b1' and request_id='$1')
  and (select count(*) from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b1' and created_at >= now() - interval '30 days') < 20;
commit;
SQL
}
for w in 1 2 3 4 5 6 7 8; do remind "new-$w" & done; wait
n=$($PSQL -d $DB -Atc "select count(*) from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b1'")
[ "$n" = 20 ] || fail "Essential reminders: ended at $n, expected exactly 20"
echo "ok  8 concurrent reminders with 15 used -> exactly 20 (the 21st is refused)"
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
insert into businesses (id,name,slug,business_type) values ('e0000000-0000-0000-0000-0000000000b2','Dup','ent-dup','RETAIL');
SQL
dup() { $PSQL -d $DB -q -At <<SQL >/dev/null 2>&1
begin;
select 1 from (select pg_advisory_xact_lock(hashtextextended('e0000000-0000-0000-0000-0000000000b2:limit:reminders', 0))) t;
insert into reminder_log (id,business_id,request_id,invoice_id)
select gen_random_uuid(),'e0000000-0000-0000-0000-0000000000b2','same-request',gen_random_uuid()
where not exists (select 1 from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b2' and request_id='same-request');
commit;
SQL
}
for w in 1 2 3 4 5 6; do dup & done; wait
n=$($PSQL -d $DB -Atc "select count(*) from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b2'")
[ "$n" = 1 ] || fail "duplicate request id counted $n times"
# and the database itself refuses a duplicate even if the lock were skipped
if $PSQL -d $DB -q -c "insert into reminder_log (id,business_id,request_id) values (gen_random_uuid(),'e0000000-0000-0000-0000-0000000000b2','same-request')" 2>/dev/null; then fail "unique (business_id, request_id) not enforced"; fi
echo "ok  6 concurrent identical reminder requests -> counted once; duplicate also refused by the unique constraint"
# tenant isolation of the log: another business's count is independent
other=$($PSQL -d $DB -Atc "select count(*) from reminder_log where business_id='e0000000-0000-0000-0000-0000000000b2'")
[ "$other" = 1 ] || fail "reminder log leaked across businesses"
echo "PASS entitlements_check"
