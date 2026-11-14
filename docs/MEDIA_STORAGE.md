# Image and media storage: audit (2026-11-14)

Established by reading the code and migrations. Nothing here depends on assumptions about dependencies: `pom.xml` and `application.yml` contain **no** S3, R2, Cloudinary or Supabase client or setting, and no upload code writes to the filesystem.

## Verdict
**Every image is stored as binary (`bytea`) inside PostgreSQL.** The database holds the picture bytes themselves, not URLs to an external store. The "URL" a product carries is just a link back to ShopScale's own API.

## Breakdown
| Media | Table / column | Upload endpoint | Served by | Limits |
|---|---|---|---|---|
| Product main image | `product_images.data` (`gallery=false`); `products.image_url` holds the link | `POST /api/products/{id}/image` (`ProductImageController`) | `GET /api/public/images/{id}` (public) | 600 KB; JPEG/PNG/WebP |
| Product gallery photos | `product_images.data` (`gallery=true`, `position`), max 8 per product | `POST /api/store/products/{id}/gallery` (`StoreExtrasController`) | same public URL | 600 KB |
| Storefront logo | `store_logos.data` (own table so settings reads stay light); type in `store_settings.logo_type` | `POST /api/store/logo` | `GET /api/public/store/{slug}/logo` | 300 KB |
| Invoice logo | `invoice_settings.logo` + `logo_type` | `POST /api/invoices/settings/logo` | embedded into generated PDFs only, not served as an image | 300 KB; PNG/JPEG |
| Banners, business profile image, user avatars | **do not exist** | | | |
| Other documents | none uploaded (PDFs are generated on demand, not stored) | | | |

Global cap: `spring.servlet.multipart` 1 MB. The browser shrinks pictures before upload.

## How it works
* **Upload:** the merchant is authenticated; `@PreAuthorize` checks the permission (`PRODUCTS_EDIT`, `STORE_EDIT`, `SETTINGS_EDIT`); the product is loaded with the **JWT's** business id, so another merchant's product id is "not found". The file type is decided from the bytes' magic numbers (not the filename or declared type); size is checked server-side.
* **Retrieval:** public `GET /api/public/images/{uuid}` returns the bytes with `Content-Type` from the sniffed type, `nosniff`, `Content-Security-Policy: default-src 'none'` and `Cache-Control: public, max-age=30d, immutable`.
* **Association:** `product_images.business_id` and `product_id`; `products.image_url` stores `{PUBLIC_API_URL}/api/public/images/{id}`. Because the URL embeds `PUBLIC_API_URL`, changing that host changes how new links are written, and old rows keep working only while the API host that serves them still exists.
* **Isolation:** merchants can only add/remove images of their own products (tenant check). The public URL is a capability link: anyone who has the random UUID can fetch that image, which is intended for storefront pictures. UUIDs are not guessable, but the endpoint does not check that the store is currently published.
* **Persistence:** because the data is in PostgreSQL, uploaded images survive app restarts and redeploys as long as the database volume does (`pgdata` in `docker-compose.yml`) and are covered by whatever database backups exist. There is nothing on the application server's disk to lose.
* **Deletion:** `product_images` rows are `ON DELETE CASCADE` from the product and the business, so they go with them. Before this change, replacing or removing a product's main picture left the old row behind (orphaned bytes). That is now fixed (see below).

## Is it production-ready?
Fine for the current stage; not a good long-term place for media.

Reasons it is acceptable now: no extra service or cost, one backup covers everything, transactional with the product, safe type checks, tenant-checked uploads, tiny files (client-side resizing).

Concerns, in order:
1. **Database growth and backup cost.** Every image enlarges the primary database and every backup/restore. Roughly: 100 products x 3 photos x 300 KB is about 90 MB per merchant, so 100 active merchants is about 9 GB of image data in Postgres.
2. **Delivery load.** Each image view is an API request plus a database read, served by the Java app, not a CDN. The 30-day public cache header helps browsers, but there is no CDN in front, so a busy storefront costs app and database capacity.
3. **No image variants.** One size is stored; thumbnails are the full file scaled by CSS.
4. **Catalogue read amplification (fixed).** The storefront catalogue loaded every gallery picture's bytes just to list URLs (`ProductImage.data` is marked lazy, but without bytecode enhancement Hibernate loads it anyway). It now reads ids only.
5. Public endpoint does not check the store is published (low risk; UUID links).

## Where images should live
Object storage behind a CDN is the normal SaaS design: the database keeps only metadata (key, type, size, owner), the bytes live in a bucket, and the browser fetches them straight from the CDN.

| Option | Storage cost | Delivery cost | CDN | Transforms | Effort for ShopScale | Verdict |
|---|---|---|---|---|---|---|
| Stay in PostgreSQL | counted in DB size | app + DB load | none | none | none | fine for MVP |
| **Cloudflare R2** | $0.015 / GB-month, first 10 GB free | **egress free** (Cloudflare docs, checked 2026-11-14) | yes (public bucket / custom domain) | via Cloudflare Images/Workers (optional, paid) | moderate: S3-compatible API | **best fit** |
| AWS S3 | about $0.023 / GB-month | per-GB egress fees, CloudFront extra | CloudFront | Lambda or third party | moderate, most IAM work | cost grows with traffic |
| Supabase Storage | included allowance then per-GB | included allowance then per-GB | yes | basic resize on higher plans | easy **only if** Supabase is already the stack | good if already on Supabase |
| Cloudinary | credit-based free tier | included in credits | yes | **best built-in** resize/optimise | easy SDK | convenient, but pricing jumps when the free credits run out |
| Local disk on the app server | cheap | app load | none | none | low | **not recommended**: lost on redeploy/scale-out unless a persistent volume is managed |

**Recommendation:** keep the database storage for launch (it works and avoids a new bill); plan a move to **Cloudflare R2** when stored images reach roughly 5-10 GB or storefront traffic makes image requests a noticeable share of API load. R2 has no egress fee (the cost that usually surprises image-heavy shops), a free tier that covers early growth, S3-compatible tooling, and a CDN in front. Choose Cloudinary instead only if on-the-fly resizing matters more than cost control. Do not use local disk.

## Recommended target architecture (not implemented)
* **Key layout:** `merchants/{businessId}/products/{productId}/{imageId}.webp`, `merchants/{businessId}/store/logo.webp`. Layout by business id makes per-merchant export, quota and deletion easy; the id is in the key, never trusted from the request.
* **Upload flow:** browser asks the backend for a short-lived presigned upload URL (backend checks the JWT, permission, product ownership, size and type limit), uploads straight to the bucket, then confirms; the backend verifies the object (magic bytes, size) and writes metadata.
* **Database:** keep `product_images` but replace `data bytea` with `object_key`, `content_type`, `size_bytes`, `width`, `height`; keep `business_id` and `product_id`.
* **Delivery:** public bucket on a custom domain (e.g. `img.shopscale.com.ng`) with long cache headers; generate 2-3 sizes (thumb 320, card 800, full 1600) at upload.
* **Deletion:** deleting a product or replacing an image deletes its objects; a nightly job removes objects without a database row.
* **Backup:** enable bucket versioning or a weekly replication to a second bucket; the database backup alone no longer contains images.

### Migration plan if/when you decide to move (do not do this blindly)
1. Add the new columns (nullable) and the storage client behind a flag; new uploads go to R2.
2. Backfill script copies each existing `bytea` to R2, checks size/hash, then writes `object_key`; run in batches, resumable, read-only on the old data.
3. Keep `/api/public/images/{id}` working: for migrated rows it redirects (302) to the CDN URL, so **existing image URLs and `products.image_url` values never break**.
4. Only after verification and a backup, null out migrated `data` columns (or keep them one release for rollback).
* **Rollback:** the old bytes stay until step 4, so switching the flag back restores the old behaviour.
* **Cost:** at 10 GB: free; at 50 GB: about $0.60/month storage, no egress, plus request fees well under $1 for typical shop traffic.

## Changes made in this audit (necessary fixes only; no data migrated or deleted)
1. Storefront catalogue no longer loads gallery image bytes (`ProductImageRepository.galleryRefs`).
2. Replacing or removing a product's main picture now deletes the replaced image row (only the one the product pointed to; never another merchant's, never gallery photos), instead of leaving orphans.
