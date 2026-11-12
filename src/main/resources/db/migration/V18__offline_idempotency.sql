-- V18: expenses recorded offline are sent with a device-generated id so a retry can never record them twice.
ALTER TABLE expenses ADD COLUMN client_request_id varchar(64);
CREATE UNIQUE INDEX uq_expense_client_request ON expenses(business_id, client_request_id) WHERE client_request_id IS NOT NULL;
