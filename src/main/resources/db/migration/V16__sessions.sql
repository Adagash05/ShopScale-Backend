-- Sessions: device label, when the sign-in started, and when a refresh token was rotated (not revoked by logout).
-- rotated_at lets the server tell a normal rotation (a second tab or a lost response retrying within seconds)
-- from a token that was revoked on purpose by logout or by theft detection.
ALTER TABLE refresh_tokens ADD COLUMN rotated_at timestamptz;
ALTER TABLE refresh_tokens ADD COLUMN user_agent varchar(255);
ALTER TABLE refresh_tokens ADD COLUMN session_started_at timestamptz;
UPDATE refresh_tokens SET session_started_at = created_at;
CREATE INDEX idx_refresh_user_active ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
