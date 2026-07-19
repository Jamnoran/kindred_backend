package com.kindred.api.profile

class ProfileNotFoundException : RuntimeException("profile not found")

class UnknownInterestException(slug: String) : RuntimeException("unknown interest: $slug")

class LocationNotSetException : RuntimeException("set your location first — PUT /api/v1/profile/location")

/** PUT /profile/location with exactly one of lat/lng → 400. */
class IncompleteCoordinatesException : RuntimeException("provide both lat and lng, or neither")

/** Visibility-only PUT /profile/location before any location was ever stored → 422. */
class VisibilityWithoutLocationException :
    RuntimeException("no stored location to apply visibility to — include lat and lng")
class LocationNotSetException : RuntimeException("location not set")
