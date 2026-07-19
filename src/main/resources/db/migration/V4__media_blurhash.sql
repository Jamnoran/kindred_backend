-- Chat media gets the same blurhash placeholder profile photos have (§6B):
-- clients render it while the image is pending or before the signed URL loads.
ALTER TABLE media
    ADD COLUMN blurhash VARCHAR(64) NULL AFTER moderation_status;
