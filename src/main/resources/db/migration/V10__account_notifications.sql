-- Password reset / email verification tokens, notifications.
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
-- Existing accounts were created before verification existed; do not lock them into a banner.
UPDATE users SET email_verified = true;

CREATE TABLE auth_tokens (
  id         uuid PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       varchar(10) NOT NULL CHECK (kind IN ('RESET','VERIFY')),
  token_hash varchar(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id, kind);

CREATE TABLE notifications (
  id                uuid PRIMARY KEY,
  business_id       uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  audience_permission varchar(60),             -- null = every member; otherwise members holding this permission
  type              varchar(40) NOT NULL,
  title             varchar(200) NOT NULL,
  body              varchar(500),
  link              varchar(200),
  dedupe_key        varchar(120),
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_business ON notifications(business_id, created_at DESC);
CREATE UNIQUE INDEX uq_notifications_dedupe ON notifications(business_id, dedupe_key) WHERE dedupe_key IS NOT NULL;

CREATE TABLE notification_reads (
  notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  read_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (notification_id, user_id)
);
