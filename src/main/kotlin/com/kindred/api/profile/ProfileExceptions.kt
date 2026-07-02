package com.kindred.api.profile

class ProfileNotFoundException : RuntimeException("profile not found — create one with PUT /api/v1/profile")

class UnknownInterestException(slug: String) : RuntimeException("unknown interest: $slug")

class LocationNotSetException : RuntimeException("set your location first — PUT /api/v1/profile/location")
