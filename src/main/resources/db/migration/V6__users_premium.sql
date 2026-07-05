-- Premium: a one-time paid upgrade per user (no subscription, never expires).
-- NULL = free account; a timestamp records when the upgrade was purchased.
ALTER TABLE users
    ADD COLUMN premium_since TIMESTAMP NULL DEFAULT NULL;
