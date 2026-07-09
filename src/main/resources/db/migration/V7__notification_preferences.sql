-- Offline-notification delivery choices. One row per (user, notification type,
-- channel); a missing row means the default: enabled. Rows are fully replaced by
-- PUT /notification-preferences.

CREATE TABLE notification_preferences (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    notification_type VARCHAR(32)     NOT NULL,
    channel           VARCHAR(32)     NOT NULL,
    enabled           TINYINT(1)      NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_np_user_type_channel (user_id, notification_type, channel),
    CONSTRAINT fk_np_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
