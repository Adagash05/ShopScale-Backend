-- Token issuing retires the user's open tokens (update ... where user_id = ? and kind = ? and used_at is null) and the resend
-- cooldown looks for a recent open token. Without an index both scanned the whole table while holding row locks.
CREATE INDEX IF NOT EXISTS idx_auth_tokens_open ON auth_tokens (user_id, kind) WHERE used_at IS NULL;
