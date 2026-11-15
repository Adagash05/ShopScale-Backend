# Cloudflare R2 setup

Until every variable below is set, ShopScale keeps storing images in PostgreSQL exactly as before. Nothing breaks if you do not do this yet.

## What you do in Cloudflare (manual, about 15 minutes)
1. **Create the bucket.** Cloudflare dashboard → R2 Object Storage → Create bucket. Name it, for example, `shopscale-media`. Leave it private.
2. **Create an API token.** R2 → Manage API tokens → Create API token. Permission **Object Read & Write**, scoped to **this bucket only**. Copy the *Access Key ID* and *Secret Access Key* (the secret is shown once). Do **not** use a token with admin rights.
3. **Find your account id.** It is in the R2 overview page (also in the S3 endpoint `https://<account id>.r2.cloudflarestorage.com`).
4. **Connect a public domain.** Bucket → Settings → Public access → Custom domains → add, for example, `img.shopscale.com.ng` (the domain must be on Cloudflare DNS; Cloudflare adds the record). Wait until it shows *Active*. Do not use the `r2.dev` address in production: it is rate limited and has no caching.
5. *(Optional)* Cache rule: cache everything on `img.shopscale.com.ng`. Keys never change for a given picture, so long caching is safe.

## What you set on the server
```
CLOUDFLARE_R2_ACCOUNT_ID=<account id>
CLOUDFLARE_R2_ACCESS_KEY_ID=<access key id>
CLOUDFLARE_R2_SECRET_ACCESS_KEY=<secret access key>
CLOUDFLARE_R2_BUCKET_NAME=shopscale-media
CLOUDFLARE_R2_PUBLIC_BASE_URL=https://img.shopscale.com.ng
# optional
CLOUDFLARE_R2_ENDPOINT=            # default https://<account id>.r2.cloudflarestorage.com
MEDIA_STORAGE_MODE=AUTO            # DB forces PostgreSQL (rollback switch)
MEDIA_SERVE_MODE=REDIRECT          # PROXY = the API streams images itself
```
Put them in your host's secret store. Never commit them, never put them in the frontend. Restart the API.

## Check it works
1. Sign in at `/super-admin/login` → **Storage**. The R2 panel should say *Active* (if not, it lists exactly which variable is missing or wrong).
2. Press **Test connection**. It writes, reads back and deletes a small object under `healthcheck/` and reports the result. It never prints credentials.
3. As a merchant, upload a product picture. In **Storage → Inspect records** it appears as `PRODUCT_IMAGE · R2 · VERIFIED`. Open the picture's address `/api/public/images/<id>`: it redirects to `https://img.shopscale.com.ng/merchants/<business id>/products/<product id>/<image id>.jpg`.
4. Then migrate older images: see `MIGRATION_AND_ROLLBACK.md`.

## How it behaves
* **Keys:** `merchants/{businessId}/products/{productId}/{imageId}.{jpg|png|webp}`, `merchants/{businessId}/store/logo-{hash}.{ext}`, `merchants/{businessId}/invoices/logo-{hash}.{ext}`. The business id always comes from the signed-in user or the database row, never from the request. Logo keys carry a short content hash so a replaced logo gets a new address and no CDN serves the old one.
* **Sizes:** for product pictures wider than 320 / 800 px ShopScale also stores a 320 px and an 800 px copy (`…_320.jpg`, `…_800.jpg`); `/api/public/images/<id>?size=thumb|standard` uses them. Originals are always kept. Logos are not resized. Copies are JPEG (or PNG when the picture has transparency), **not WebP**: the Java runtime cannot encode WebP without a paid or native library. WebP uploads are accepted and stored as they are.
* **Existing addresses keep working:** `GET /api/public/images/{id}`, the storefront logo address and `product.image_url` are unchanged; they redirect to the image domain (or stream, with `MEDIA_SERVE_MODE=PROXY`) when the image is in R2 and answer from the database otherwise.
* **Failure handling:** if R2 is down during an upload the picture is saved in the database instead and the merchant sees success. The failure is recorded under Storage → Recent failures.
* **Headers:** images carry `X-Content-Type-Options: nosniff`; proxied responses add a restrictive Content-Security-Policy and long public caching.
* **Cost:** R2 charges for storage and operations, not for bandwidth out. ShopScale shows sizes it recorded, which are estimates, not your Cloudflare bill.

## Not verified here
The code was tested against a fake S3 server that checks AWS Signature V4 signatures (and against AWS's published signature examples). It has **not** been run against a real Cloudflare account, because no credentials exist in the build environment. The first Test connection on your server is the real test.
