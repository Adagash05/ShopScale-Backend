# Paystack credential encryption and Growth price (23 Nov 2026)

## 1. Encryption
**Cause of "missing encryption key":** merchants' Paystack keys are encrypted with AES-256-GCM (`SecretBox`) using `APP_ENCRYPTION_KEY`. The variable was empty in `.env`, so the server correctly refused to store a key it could not encrypt. The cipher itself was sound.

**What changed (`security/SecretBox.java`):**
- Same algorithm and stored format (`base64(12-byte IV + ciphertext + 16-byte tag)`), so anything encrypted before still decrypts; proved against the previous class in both directions.
- Key sources: `APP_ENCRYPTION_KEY` (required in production, unchanged); **development only** (`APP_ENV` not production, variable empty): a random key is created once in `~/.shopscale/dev-encryption.key` (owner-only) and reused on every restart. Production never auto-generates a key.
- A wrong-length or non-base64 key is reported precisely ("decodes to 16 bytes", "not valid base64") and never silently replaced; a corrupt key file is never overwritten. No message contains key material.
- `GET /api/subscriptions/billing-status` also reports `encryptionKeySource` (ENVIRONMENT, LOCAL_FILE or NONE).
- API responses never contain the secret: `scripts/db-checks/paystack_separation_check.sh` verifies the encrypted value is only null-checked or decrypted server-side.

**Configuration you must do:**
- Local: nothing is required any more with `APP_ENV=development`. Or set a key: PowerShell `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`.
- Production: generate one key **once** (`openssl rand -base64 32`), put it in the server's secret store as `APP_ENCRYPTION_KEY`, keep it identical across deploys and back it up. If it is lost, merchants must re-enter their Paystack keys.
- Keys saved locally under the dev key file will not decrypt on production (different key); merchants connect again there.

## 2. Growth price
Superseded: the price is now ₦14,999 (not ₦15,000). See `GROWTH_PRICE_14999.md`.
