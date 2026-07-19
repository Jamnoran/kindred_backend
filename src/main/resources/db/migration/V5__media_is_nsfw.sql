-- Per-surface NSFW policy (§9): chat approves NSFW images flagged instead of
-- rejecting them; clients keep flagged images blurred until the viewer opts in.
ALTER TABLE media
    ADD COLUMN is_nsfw TINYINT(1) NOT NULL DEFAULT 0 AFTER moderation_status;
