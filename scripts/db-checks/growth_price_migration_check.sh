#!/usr/bin/env bash
# Builds a scratch database at V1..V29, adds subscribers in every situation, applies V30, and checks who keeps their old price.
# Usage: PSQL="psql -h /tmp/pg -p 5544 -U postgres" scripts/db-checks/growth_price_migration_check.sh   (run from the repository root)
set -eu
PSQL=${PSQL:-psql}; DB=${DB:-ss_price_check}; M=src/main/resources/db/migration
$PSQL -q -d postgres -c "drop database if exists $DB" -c "create database $DB" 2>/dev/null
for f in $(ls $M | sort -V | grep -Ev '^V(30|31)__'); do $PSQL -d $DB -v ON_ERROR_STOP=1 -q -f $M/$f >/dev/null; done
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
insert into businesses (id,name,slug,business_type) select ('f0000000-0000-0000-0000-00000000000'||n)::uuid, 'B'||n, 'price-'||n, 'RETAIL' from generate_series(1,8) n;
-- 1 growth monthly active paid | 2 growth annual active paid | 3 growth cancelled in period, paid | 4 growth trialing, never paid
-- 5 essential paid | 6 growth expired, paid long ago | 7 business paid | 8 growth active, comped (no payment)
insert into subscriptions (id,business_id,plan_code,billing_cycle,status,current_period_start,current_period_end)
select gen_random_uuid(), ('f0000000-0000-0000-0000-00000000000'||n)::uuid, p, c, s, now()-interval '10 days', now()+interval '20 days'
from (values (1,'GROWTH','MONTHLY','ACTIVE'),(2,'GROWTH','ANNUAL','ACTIVE'),(3,'GROWTH','MONTHLY','CANCELLED'),(4,'GROWTH','MONTHLY','TRIALING'),(5,'ESSENTIAL','MONTHLY','ACTIVE'),(6,'GROWTH','MONTHLY','EXPIRED'),(7,'BUSINESS','MONTHLY','ACTIVE'),(8,'GROWTH','MONTHLY','ACTIVE')) v(n,p,c,s);
insert into subscription_payments (id,business_id,plan_code,billing_cycle,kind,amount,list_price,reference,status)
select gen_random_uuid(), ('f0000000-0000-0000-0000-00000000000'||n)::uuid, p, c, 'NEW', a, a, 'ref-'||n, 'SUCCESS'
from (values (1,'GROWTH','MONTHLY',11999),(2,'GROWTH','ANNUAL',119990),(3,'GROWTH','MONTHLY',11999),(5,'ESSENTIAL','MONTHLY',4999),(6,'GROWTH','MONTHLY',11999),(7,'BUSINESS','MONTHLY',24999)) v(n,p,c,a);
SQL
$PSQL -d $DB -v ON_ERROR_STOP=1 -q -f $M/V30__growth_price_14999_keep_existing_subscribers.sql
$PSQL -d $DB -v ON_ERROR_STOP=1 -q <<'SQL'
do $$ declare r record; begin
  -- prices
  for r in select code, monthly_price m, annual_price a from plans where code in ('FREE','ESSENTIAL','GROWTH','BUSINESS') loop
    if r.code='GROWTH' and (r.m<>14999 or r.a<>149990) then raise exception 'growth price wrong: % %', r.m, r.a; end if;
    if r.code='ESSENTIAL' and (r.m<>4999 or r.a<>49990) then raise exception 'essential changed'; end if;
    if r.code='BUSINESS' and (r.m<>24999 or r.a<>249990) then raise exception 'business changed'; end if;
    if r.code='FREE' and (r.m<>0 or r.a<>0) then raise exception 'free changed'; end if;
  end loop;
  if (select (max_users,max_locations,max_items,max_suppliers,recommended,sort_order) from plans where code='GROWTH') is distinct from (5,2,500,50,true,2) then raise exception 'growth limits changed'; end if;
  -- who keeps the old price
  if (select locked_price from subscriptions where business_id='f0000000-0000-0000-0000-000000000001') <> 11999 then raise exception 'monthly paying growth not kept'; end if;
  if (select locked_price from subscriptions where business_id='f0000000-0000-0000-0000-000000000002') <> 119990 then raise exception 'annual paying growth not kept'; end if;
  if (select locked_price from subscriptions where business_id='f0000000-0000-0000-0000-000000000003') <> 11999 then raise exception 'cancelled-in-period growth not kept'; end if;
  if (select count(*) from subscriptions where locked_price is not null and business_id::text in
      ('f0000000-0000-0000-0000-000000000004','f0000000-0000-0000-0000-000000000005','f0000000-0000-0000-0000-000000000006','f0000000-0000-0000-0000-000000000007','f0000000-0000-0000-0000-000000000008')) <> 0
  then raise exception 'a subscriber who should pay the new price was locked'; end if;
  if (select count(*) from subscriptions where locked_price is not null) <> 3 then raise exception 'expected exactly 3 locked subscribers'; end if;
  begin update subscriptions set locked_price = 5 where business_id='f0000000-0000-0000-0000-000000000005'; raise exception 'half-set lock accepted';
  exception when check_violation then null; end;
end $$;
select 'PASS: Growth 14,999 / 149,990; other plans and Growth limits unchanged; exactly the 3 paying Growth subscribers keep 11,999 / 119,990' as result;
SQL
$PSQL -q -d postgres -c "drop database $DB"
