
-- V21: Fix store review rating column type.
-- Align PostgreSQL SMALLINT with the Java Integer entity mapping.

ALTER TABLE store_reviews
    ALTER COLUMN rating TYPE INTEGER
    USING rating::INTEGER;
