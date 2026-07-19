-- Coarse, human-readable place name for the stored location (nearest GeoNames
-- city/town, derived at write time). Never finer than city granularity, so it is
-- safe to show in the UI regardless of location_visibility. NULL = no location set.
ALTER TABLE profiles
    ADD COLUMN location_label VARCHAR(120) NULL AFTER location_visibility;
