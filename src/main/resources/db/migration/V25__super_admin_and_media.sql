-- ShopScale Super Admin Console + object-storage (R2) metadata. Additive only: no existing column is dropped, no data is rewritten.

-- ---------- platform identity: separate from every business role ----------
ALTER TABLE users
    ADD COLUMN platform_role        varchar(30) CHECK (platform_role IS NULL OR platform_role IN ('SUPER_ADMIN')),
    ADD COLUMN must_change_password boolean     NOT NULL DEFAULT false,
    ADD COLUMN last_login_at        timestamptz,
    ADD COLUMN password_changed_at  timestamptz,
    ADD COLUMN failed_logins        integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         timestamptz;
CREATE INDEX idx_users_platform_role ON users(platform_role) WHERE platform_role IS NOT NULL;

-- A Super Admin sign-in. The access token carries the session id, so logout / password change / suspension end it at once.
CREATE TABLE admin_sessions (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    ip          varchar(64),
    user_agent  varchar(300)
);
CREATE INDEX idx_admin_sessions_user ON admin_sessions(user_id);

-- ---------- administrative audit log (append-only) ----------
CREATE TABLE admin_audit_log (
    id          uuid PRIMARY KEY,
    actor_id    uuid,
    actor_email varchar(255),
    action      varchar(60)  NOT NULL,
    target_type varchar(40),
    target_id   varchar(80),
    business_id uuid,
    success     boolean      NOT NULL DEFAULT true,
    reason      varchar(500),
    ip          varchar(64),
    metadata    text,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_time ON admin_audit_log(created_at DESC);
CREATE INDEX idx_admin_audit_action ON admin_audit_log(action, created_at DESC);
CREATE INDEX idx_admin_audit_target ON admin_audit_log(target_type, target_id);

CREATE FUNCTION admin_audit_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'admin_audit_log is append-only'; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_admin_audit_immutable BEFORE UPDATE OR DELETE ON admin_audit_log FOR EACH ROW EXECUTE FUNCTION admin_audit_append_only();
CREATE TRIGGER trg_admin_audit_no_truncate BEFORE TRUNCATE ON admin_audit_log FOR EACH STATEMENT EXECUTE FUNCTION admin_audit_append_only();

-- ---------- support ----------
CREATE TABLE support_tickets (
    id           uuid PRIMARY KEY,
    business_id  uuid         REFERENCES businesses(id),
    user_id      uuid         REFERENCES users(id),
    category     varchar(30)  NOT NULL DEFAULT 'OTHER' CHECK (category IN ('BILLING','ACCOUNT','STORE','PAYMENTS','INVENTORY','SALES','BUG','OTHER')),
    priority     varchar(10)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    status       varchar(24)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','WAITING_FOR_CUSTOMER','RESOLVED','CLOSED')),
    subject      varchar(200) NOT NULL,
    description  varchar(4000) NOT NULL,
    assigned_to  uuid         REFERENCES users(id),
    resolution   varchar(2000),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    resolved_at  timestamptz,
    CONSTRAINT chk_ticket_resolved CHECK (status NOT IN ('RESOLVED','CLOSED') OR resolved_at IS NOT NULL)
);
CREATE INDEX idx_tickets_status ON support_tickets(status, updated_at DESC);
CREATE INDEX idx_tickets_business ON support_tickets(business_id);

CREATE TABLE support_ticket_notes (
    id         uuid PRIMARY KEY,
    ticket_id  uuid          NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    author_id  uuid          REFERENCES users(id),
    internal   boolean       NOT NULL DEFAULT true,   -- internal = administrators only; false = visible to the merchant
    body       varchar(4000) NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_notes_ticket ON support_ticket_notes(ticket_id, created_at);

-- ---------- announcements (shown in the merchant app while published and inside their window) ----------
CREATE TABLE platform_announcements (
    id          uuid PRIMARY KEY,
    title       varchar(160)  NOT NULL,
    body        varchar(2000) NOT NULL,
    level       varchar(10)   NOT NULL DEFAULT 'INFO' CHECK (level IN ('INFO','WARNING','CRITICAL')),
    audience    varchar(40)   NOT NULL DEFAULT 'ALL',          -- ALL, or PLAN:<plan code> (businesses currently on that plan)
    status      varchar(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','INACTIVE')),
    starts_at   timestamptz,                                   -- while PUBLISHED, shown from here (null = from publication)
    ends_at     timestamptz,
    created_by  uuid          REFERENCES users(id),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_announcement_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

-- ---------- platform settings (non-secret values only) ----------
CREATE TABLE platform_settings (
    key         varchar(60)  PRIMARY KEY,
    value       varchar(500) NOT NULL,
    updated_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
INSERT INTO platform_settings (key, value) VALUES ('platform_name', 'ShopScale'), ('support_email', ''), ('support_phone', '');

-- ---------- business suspension record (the status column already exists) ----------
ALTER TABLE businesses ADD COLUMN status_reason varchar(500), ADD COLUMN status_changed_at timestamptz;
ALTER TABLE businesses ADD CONSTRAINT chk_business_status CHECK (status IN ('ACTIVE','SUSPENDED')) NOT VALID;   -- NOT VALID: never fails on rows written before this migration

-- ---------- media metadata: one row per stored image, whatever table holds its bytes ----------
-- The legacy BYTEA columns stay exactly as they are. A row here with storage='R2' and status='VERIFIED' means the object was uploaded and
-- its size and SHA-256 were checked; only then is it served from object storage. Nothing here deletes old bytes.
CREATE TABLE media_objects (
    id            uuid PRIMARY KEY,
    business_id   uuid         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    kind          varchar(20)  NOT NULL CHECK (kind IN ('PRODUCT_IMAGE','STORE_LOGO','INVOICE_LOGO')),
    owner_id      uuid         NOT NULL,          -- product_images.id, or the business id for the two logos
    storage       varchar(4)   NOT NULL DEFAULT 'DB' CHECK (storage IN ('DB','R2')),
    object_key    varchar(300),
    thumb_key     varchar(300),
    standard_key  varchar(300),
    content_type  varchar(30),
    size_bytes    integer,
    sha256        char(64),
    status        varchar(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','FAILED')),
    attempts      integer      NOT NULL DEFAULT 0,
    last_error    varchar(500),
    verified_at   timestamptz,
    source        varchar(10)  NOT NULL DEFAULT 'MIGRATION' CHECK (source IN ('MIGRATION','UPLOAD')),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (kind, owner_id),
    CONSTRAINT chk_media_verified CHECK (status <> 'VERIFIED' OR (object_key IS NOT NULL AND sha256 IS NOT NULL AND size_bytes IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX idx_media_status ON media_objects(status, kind);
CREATE INDEX idx_media_business ON media_objects(business_id);
CREATE UNIQUE INDEX idx_media_object_key ON media_objects(object_key) WHERE object_key IS NOT NULL;

-- New uploads can live only in object storage, so the byte columns may be empty for them.
ALTER TABLE product_images ALTER COLUMN data DROP NOT NULL;
ALTER TABLE store_logos ALTER COLUMN data DROP NOT NULL;

-- A recorded failed upload attempt, for the Storage dashboard.
CREATE TABLE media_events (
    id          uuid PRIMARY KEY,
    business_id uuid,
    kind        varchar(20),
    event       varchar(20) NOT NULL CHECK (event IN ('UPLOAD_OK','UPLOAD_FAILED','MIGRATED','MIGRATE_FAILED','RESTORED','VERIFY_FAILED')),
    detail      varchar(500),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_events_time ON media_events(created_at DESC);
