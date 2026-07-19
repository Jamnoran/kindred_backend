-- Gender identity + relationship styles (LGBTQ+ and ethical-non-monogamy support).
-- gender is optional and self-identified (trans women are women, etc. — there is
-- deliberately no separate category). Orientation is not stored as a label: it is
-- expressed as preferences.genders ("show me"), enforced mutually in discovery.
-- JSON list columns mirror the looking_for pattern (§5).

ALTER TABLE profiles
    ADD COLUMN gender ENUM ('woman', 'man', 'nonbinary') NULL AFTER bio,
    ADD COLUMN relationship_styles JSON NULL AFTER looking_for;

ALTER TABLE preferences
    ADD COLUMN genders JSON NULL AFTER age_max,
    ADD COLUMN relationship_styles JSON NULL AFTER looking_for;
