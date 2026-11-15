# Moving images from PostgreSQL to R2, and rolling back

## Principles
* Nothing is deleted from PostgreSQL by this process. The old bytes stay until you decide, outside this tool, to remove them (see "After migrating").
* An image counts as migrated only after R2 returns **exactly** the same size and SHA-256 as the database bytes. Anything else is recorded as FAILED with the reason and retried.
* Progress lives in the table `media_objects`, so stopping, a crash or a restart simply resumes.
* Serving prefers a VERIFIED R2 object and falls back to the database bytes; a problem in R2 therefore never takes an old image offline.

## Before you start (backup, required by policy)
1. Take a database backup (`pg_dump` or your host's snapshot) and keep it until the migration is complete and checked.
2. Configure R2 and press **Test connection** (see `CLOUDFLARE_R2_SETUP.md`).
3. Deploy this release. Migration V25 only **adds** tables and nullable columns; it changes no existing rows.

## Migrate (Super Admin → Storage)
1. **Find images** registers every image that exists only in the database (product pictures, gallery pictures, store logos, invoice logos) as PENDING. It can be repeated safely.
2. **Move next 25** uploads up to 25, verifies each, and marks it VERIFIED. Repeat until *Waiting to migrate* reaches 0. Each press takes seconds. Product pictures also get 320 / 800 px copies.
3. **Verify moved images** (existence and size) and, once, **Deep verify** (re-downloads and compares SHA-256).
4. **Retry failed** re-queues failures (after you fix the cause shown in *Recent failures*). An item is retried automatically up to 5 times per queueing.
5. Check **Consistency**: it must show zeros. **Scan for orphan objects** lists R2 objects no record refers to; you may delete a selection (each is re-checked just before deletion). There is no "delete everything" button.

While migrating, merchants keep working; new uploads already go to R2.

## Rollback
Use it if R2 misbehaves or you want to return to the database.
1. Set `MEDIA_STORAGE_MODE=DB` and restart. New uploads now go to the database and no migration batch will run.
2. Storage → **Roll back a batch** (25 at a time, repeat until 0 remain in R2). For images that still have database bytes it just switches the record back; for images uploaded after R2 was enabled (R2 only) it downloads them, checks the SHA-256 and writes the bytes into the database first. An image it cannot restore is left in R2 untouched and reported.
3. R2 objects are **not** deleted by rollback. You can delete the bucket contents later in Cloudflare once you are sure.

Emergency without the console: switching `MEDIA_STORAGE_MODE=DB` alone does not change how existing R2 images are served. If R2 itself is unreachable, serving in REDIRECT mode sends browsers to the image domain; set `MEDIA_SERVE_MODE=PROXY` or run the rollback batches.

## After migrating
The database bytes remain, which doubles storage. Deleting them is a **destructive** step this release does not perform. When you decide to: keep the backup, run **Deep verify** with zero problems, then (outside this tool) run per-table `UPDATE … SET data = NULL` in small batches for rows whose `media_objects` record is `R2 / VERIFIED`, and `VACUUM FULL` in a maintenance window. Get written approval first; a restore afterwards needs the rollback batches or the backup.

## What each state means
`PENDING` waiting · `VERIFIED` stored in R2 and checked · `FAILED` see `last_error` · storage `DB` (serving from PostgreSQL) or `R2`.
