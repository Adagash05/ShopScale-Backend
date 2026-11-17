-- Kitchen lifecycle, menu availability, wastage and production recording, and the catering module.
-- Additive only: existing tables keep their data, nothing is dropped or recreated.

-- ---------------------------------------------------------------- menu availability
ALTER TABLE products ADD COLUMN sold_out boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------- kitchen order lifecycle
-- The order-level stage (pending, accepted, preparing, ready, served, paid, cancelled) is derived from these
-- timestamps and the item statuses, so there is one source of truth and nothing to keep in sync.
ALTER TABLE kitchen_orders
  ADD COLUMN accepted_at    timestamptz,
  ADD COLUMN accepted_by    uuid,
  ADD COLUMN closed_at      timestamptz,
  ADD COLUMN cancel_reason  varchar(300),
  ADD COLUMN cancelled_by   uuid,
  ADD COLUMN reversed_at    timestamptz,
  ADD COLUMN reverse_reason varchar(300),
  ADD COLUMN reversed_by    uuid,
  ADD COLUMN client_request_id varchar(64);
CREATE UNIQUE INDEX uq_kitchen_order_request ON kitchen_orders(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

ALTER TABLE kitchen_order_items
  ADD COLUMN started_at    timestamptz,
  ADD COLUMN ready_at      timestamptz,
  ADD COLUMN served_at     timestamptz,
  ADD COLUMN cancelled_at  timestamptz,
  ADD COLUMN cancel_reason varchar(300),
  ADD COLUMN wasted        boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------- wastage: retries must not record twice
ALTER TABLE wastage_records
  ADD COLUMN client_request_id varchar(64),
  ADD COLUMN reference         varchar(60);
CREATE UNIQUE INDEX uq_wastage_client_request ON wastage_records(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

-- ---------------------------------------------------------------- simple production (bakery, small chops, packaged food)
CREATE TABLE production_runs (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id         uuid NOT NULL REFERENCES branches(id),
  variant_id        uuid NOT NULL REFERENCES product_variants(id),
  item_name         varchar(250) NOT NULL,
  quantity          numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_cost         numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
  used_recipe       boolean NOT NULL DEFAULT false,
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_production_runs_time ON production_runs(business_id, created_at DESC);
CREATE UNIQUE INDEX uq_production_client_request ON production_runs(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

-- ---------------------------------------------------------------- catering
CREATE TABLE catering_packages (
  id              uuid PRIMARY KEY,
  business_id     uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name            varchar(120) NOT NULL,
  description     varchar(1000),
  inclusions      varchar(2000),
  base_price      numeric(14,2) NOT NULL DEFAULT 0 CHECK (base_price >= 0),
  price_per_guest numeric(14,2) NOT NULL DEFAULT 0 CHECK (price_per_guest >= 0),
  min_guests      integer NOT NULL DEFAULT 1 CHECK (min_guests >= 1),
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_catering_package_name ON catering_packages(business_id, lower(name));

-- What a package needs per guest. A line is either a stock ingredient or a menu item that has a recipe.
CREATE TABLE catering_package_lines (
  id             uuid PRIMARY KEY,
  package_id     uuid NOT NULL REFERENCES catering_packages(id) ON DELETE CASCADE,
  variant_id     uuid NOT NULL REFERENCES product_variants(id),
  qty_per_guest  numeric(14,4) NOT NULL CHECK (qty_per_guest > 0)
);
CREATE INDEX idx_catering_package_lines ON catering_package_lines(package_id);

CREATE TABLE catering_services (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(120) NOT NULL,
  description varchar(500),
  price       numeric(14,2) NOT NULL CHECK (price >= 0),
  basis       varchar(10) NOT NULL DEFAULT 'FIXED' CHECK (basis IN ('FIXED','PER_GUEST')),
  active      boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_catering_service_name ON catering_services(business_id, lower(name));

CREATE TABLE catering_events (
  id                 uuid PRIMARY KEY,
  business_id        uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  branch_id          uuid NOT NULL REFERENCES branches(id),
  number             bigint NOT NULL,
  title              varchar(150) NOT NULL,
  customer_id        uuid REFERENCES customers(id),
  customer_name      varchar(150) NOT NULL,
  customer_phone     varchar(40),
  venue              varchar(250),
  event_date         date NOT NULL,
  start_time         time,
  guests             integer NOT NULL CHECK (guests > 0),
  requirements       varchar(2000),
  status             varchar(14) NOT NULL DEFAULT 'ENQUIRY'
                     CHECK (status IN ('ENQUIRY','QUOTED','BOOKED','IN_PREPARATION','READY','COMPLETED','CANCELLED')),
  package_id         uuid REFERENCES catering_packages(id),
  package_name       varchar(120),
  base_price         numeric(14,2) NOT NULL DEFAULT 0,       -- snapshot taken when the quote is calculated
  price_per_guest    numeric(14,2) NOT NULL DEFAULT 0,
  services_total     numeric(14,2) NOT NULL DEFAULT 0,
  discount           numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
  total              numeric(14,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
  deposit_required   numeric(14,2) NOT NULL DEFAULT 0 CHECK (deposit_required >= 0),
  quote_valid_until  date,
  quoted_at          timestamptz,
  confirmed_at       timestamptz,
  prep_started_at    timestamptz,
  ready_at           timestamptz,
  completed_at       timestamptz,
  cancelled_at       timestamptz,
  cancel_reason      varchar(300),
  stock_issued_at    timestamptz,                             -- set once: the guard against deducting stock twice
  created_by         uuid,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id, number)
);
CREATE INDEX idx_catering_events_status ON catering_events(business_id, status, event_date);
CREATE INDEX idx_catering_events_customer ON catering_events(customer_id);

CREATE TABLE catering_event_services (
  id          uuid PRIMARY KEY,
  event_id    uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  service_id  uuid REFERENCES catering_services(id),
  name        varchar(120) NOT NULL,
  basis       varchar(10) NOT NULL CHECK (basis IN ('FIXED','PER_GUEST')),
  unit_price  numeric(14,2) NOT NULL CHECK (unit_price >= 0),
  quantity    integer NOT NULL DEFAULT 1 CHECK (quantity >= 1),
  line_total  numeric(14,2) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX idx_catering_event_services ON catering_event_services(event_id);

CREATE TABLE catering_payments (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  event_id          uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  kind              varchar(10) NOT NULL CHECK (kind IN ('DEPOSIT','PAYMENT','REFUND')),
  method            varchar(20) NOT NULL,
  amount            numeric(14,2) NOT NULL CHECK (amount > 0),
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_catering_payments_event ON catering_payments(event_id, created_at);
CREATE UNIQUE INDEX uq_catering_payment_request ON catering_payments(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE catering_tasks (
  id        uuid PRIMARY KEY,
  event_id  uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  title     varchar(200) NOT NULL,
  done      boolean NOT NULL DEFAULT false,
  done_at   timestamptz,
  done_by   uuid,
  sort_order integer NOT NULL DEFAULT 0
);
CREATE INDEX idx_catering_tasks_event ON catering_tasks(event_id, sort_order);

-- Ingredient plan for an event (planned quantity and the cost per unit when the plan was made).
CREATE TABLE catering_plan_lines (
  id           uuid PRIMARY KEY,
  event_id     uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  variant_id   uuid NOT NULL REFERENCES product_variants(id),
  item_name    varchar(250) NOT NULL,
  unit         varchar(20),
  planned_qty  numeric(14,3) NOT NULL CHECK (planned_qty > 0),
  unit_cost    numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
  UNIQUE (event_id, variant_id)
);

-- Every stock movement made for an event. ISSUE happens once per event (see catering_events.stock_issued_at).
CREATE TABLE catering_usage (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  event_id          uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  variant_id        uuid NOT NULL REFERENCES product_variants(id),
  kind              varchar(8) NOT NULL CHECK (kind IN ('ISSUE','EXTRA','RETURN','WASTE')),
  quantity          numeric(14,3) NOT NULL CHECK (quantity > 0),
  unit_cost         numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
  note              varchar(300),
  client_request_id varchar(64),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_catering_usage_event ON catering_usage(event_id);
CREATE UNIQUE INDEX uq_catering_usage_request ON catering_usage(business_id, client_request_id) WHERE client_request_id IS NOT NULL;

-- Costs other than ingredients. Estimated lines are plans; actual lines are real spending and also appear in Expenses.
CREATE TABLE catering_costs (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  event_id    uuid NOT NULL REFERENCES catering_events(id) ON DELETE CASCADE,
  category    varchar(80) NOT NULL,
  description varchar(300),
  amount      numeric(14,2) NOT NULL CHECK (amount > 0),
  estimated   boolean NOT NULL,
  expense_id  uuid REFERENCES expenses(id),
  client_request_id varchar(64),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_catering_costs_event ON catering_costs(event_id);
CREATE UNIQUE INDEX uq_catering_cost_request ON catering_costs(business_id, client_request_id) WHERE client_request_id IS NOT NULL;
