-- Phase 4 admin moderation: manual admin flag and ban marker.
-- is_admin has no API to set it — grant via SQL (ops decision, keeps the surface small):
--   UPDATE users SET is_admin = 1 WHERE email = '...';
-- banned_at NULL = active. Banned accounts cannot log in, lose their live sessions,
-- and are excluded from discovery. Reversible (unban), unlike deleted_at.
ALTER TABLE users
    ADD COLUMN is_admin  TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN banned_at TIMESTAMP  NULL     DEFAULT NULL;
