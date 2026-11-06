-- Staff management: custom roles, invitations, and the business a session belongs to.

CREATE TABLE roles (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name        varchar(80) NOT NULL,
  description varchar(300),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_roles_business_name ON roles(business_id, lower(name));

CREATE TABLE role_permissions (
  role_id    uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission varchar(60) NOT NULL,
  PRIMARY KEY (role_id, permission)
);

ALTER TABLE memberships
  ADD COLUMN role_id        uuid REFERENCES roles(id),
  ADD COLUMN invited_by     uuid,
  ADD COLUMN last_active_at timestamptz;

ALTER TABLE refresh_tokens ADD COLUMN business_id uuid;

CREATE TABLE invitations (
  id          uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  email       varchar(255) NOT NULL,
  full_name   varchar(150) NOT NULL,
  phone       varchar(40),
  role        varchar(20) NOT NULL,
  role_id     uuid REFERENCES roles(id),
  token_hash  varchar(64) NOT NULL UNIQUE,
  status      varchar(12) NOT NULL DEFAULT 'PENDING',
  expires_at  timestamptz NOT NULL,
  invited_by  uuid,
  email_sent  boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  accepted_at timestamptz
);
CREATE INDEX idx_invitations_business ON invitations(business_id, created_at DESC);
CREATE UNIQUE INDEX uq_invitation_pending ON invitations(business_id, lower(email)) WHERE status = 'PENDING';
