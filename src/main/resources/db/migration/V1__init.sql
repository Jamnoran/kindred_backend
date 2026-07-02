-- Baseline schema — ARCHITECTURE.md §5.
-- InnoDB everywhere, FKs enforced. Flexible per-profile bits live in JSON columns;
-- geo lives in a POINT (SRID 4326) with a SPATIAL index for ST_Distance_Sphere.

CREATE TABLE users (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    email_verified  TINYINT(1)      NOT NULL DEFAULT 0,
    dob             DATE            NOT NULL, -- 18+ gate enforced in the API
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP       NULL     DEFAULT NULL, -- soft-delete marker; GDPR erasure job hard-deletes
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE profiles (
    user_id             BIGINT UNSIGNED NOT NULL,
    display_name        VARCHAR(100)    NOT NULL,
    bio                 TEXT            NULL,
    looking_for         JSON            NULL,
    -- SPATIAL indexes require NOT NULL; unset locations sit at (0,0) until the user sets one
    location            POINT SRID 4326 NOT NULL DEFAULT (ST_SRID(POINT(0, 0), 4326)),
    location_set        TINYINT(1)      NOT NULL DEFAULT 0,
    location_visibility ENUM ('exact', 'approximate', 'hidden') NOT NULL DEFAULT 'approximate',
    last_active_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    SPATIAL INDEX idx_profiles_location (location),
    KEY idx_profiles_last_active (last_active_at),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE photos (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    profile_user_id   BIGINT UNSIGNED NOT NULL,
    storage_key       VARCHAR(255)    NOT NULL, -- random, non-enumerable (§6)
    sort_order        INT             NOT NULL DEFAULT 0,
    is_primary        TINYINT(1)      NOT NULL DEFAULT 0,
    moderation_status ENUM ('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
    blurhash          VARCHAR(64)     NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_photos_storage_key (storage_key),
    KEY idx_photos_profile (profile_user_id),
    CONSTRAINT fk_photos_profile FOREIGN KEY (profile_user_id) REFERENCES profiles (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE interests (
    id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    slug  VARCHAR(64)     NOT NULL,
    label VARCHAR(100)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interests_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE profile_interests (
    profile_user_id BIGINT UNSIGNED NOT NULL,
    interest_id     BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (profile_user_id, interest_id),
    CONSTRAINT fk_pi_profile FOREIGN KEY (profile_user_id) REFERENCES profiles (user_id),
    CONSTRAINT fk_pi_interest FOREIGN KEY (interest_id) REFERENCES interests (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE preferences (
    user_id      BIGINT UNSIGNED NOT NULL,
    distance_km  INT             NOT NULL DEFAULT 50,
    age_min      INT             NOT NULL DEFAULT 18,
    age_max      INT             NOT NULL DEFAULT 99,
    looking_for  JSON            NULL,
    dealbreakers JSON            NULL,
    weights      JSON            NULL, -- user-tunable transparent-matching weights (§7)
    PRIMARY KEY (user_id),
    CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE likes (
    from_user  BIGINT UNSIGNED NOT NULL,
    to_user    BIGINT UNSIGNED NOT NULL,
    kind       ENUM ('like', 'superlike', 'pass') NOT NULL,
    created_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (from_user, to_user),
    KEY idx_likes_to_user (to_user), -- "who liked you" is just this query — no paywall
    CONSTRAINT fk_likes_from FOREIGN KEY (from_user) REFERENCES users (id),
    CONSTRAINT fk_likes_to FOREIGN KEY (to_user) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE matches (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_a     BIGINT UNSIGNED NOT NULL,
    user_b     BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_matches_pair (user_a, user_b),
    KEY idx_matches_user_b (user_b),
    CONSTRAINT chk_matches_ordered CHECK (user_a < user_b), -- store ordered to dedupe
    CONSTRAINT fk_matches_a FOREIGN KEY (user_a) REFERENCES users (id),
    CONSTRAINT fk_matches_b FOREIGN KEY (user_b) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE conversations (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    match_id   BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversations_match (match_id),
    CONSTRAINT fk_conversations_match FOREIGN KEY (match_id) REFERENCES matches (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE media (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    storage_key       VARCHAR(255)    NOT NULL, -- private bucket/prefix, signed URLs only (§6B)
    owner_user_id     BIGINT UNSIGNED NOT NULL,
    conversation_id   BIGINT UNSIGNED NOT NULL,
    moderation_status ENUM ('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
    expires_at        TIMESTAMP       NULL     DEFAULT NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_storage_key (storage_key),
    KEY idx_media_conversation (conversation_id),
    CONSTRAINT fk_media_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_media_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE messages (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    sender_id       BIGINT UNSIGNED NOT NULL,
    body            TEXT            NULL,
    media_id        BIGINT UNSIGNED NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at         TIMESTAMP       NULL     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_messages_conversation (conversation_id, created_at),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id),
    CONSTRAINT fk_messages_media FOREIGN KEY (media_id) REFERENCES media (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE reports (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reporter_id      BIGINT UNSIGNED NOT NULL,
    reported_user_id BIGINT UNSIGNED NOT NULL,
    reason           VARCHAR(64)     NOT NULL,
    details          TEXT            NULL,
    status           ENUM ('open', 'reviewing', 'resolved', 'dismissed') NOT NULL DEFAULT 'open',
    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_reports_reported (reported_user_id),
    KEY idx_reports_status (status),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_reports_reported FOREIGN KEY (reported_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE blocks (
    blocker_id BIGINT UNSIGNED NOT NULL,
    blocked_id BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blocker_id, blocked_id), -- a block severs visibility + messaging both ways
    KEY idx_blocks_blocked (blocked_id),
    CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id),
    CONSTRAINT fk_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE moderation_events (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id   BIGINT UNSIGNED NULL, -- NULL = automated pipeline action
    subject_user_id BIGINT UNSIGNED NULL,
    action          VARCHAR(64)     NOT NULL,
    target_type     VARCHAR(32)     NULL, -- photo | media | message | profile | report …
    target_id       BIGINT UNSIGNED NULL,
    detail          JSON            NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_modevents_subject (subject_user_id),
    CONSTRAINT fk_modevents_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_modevents_subject FOREIGN KEY (subject_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
