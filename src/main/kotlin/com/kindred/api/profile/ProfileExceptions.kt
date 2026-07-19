package com.kindred.api.profile

class ProfileNotFoundException : RuntimeException("profile not found")

class UnknownInterestException(slug: String) : RuntimeException("unknown interest: $slug")

class LocationNotSetException : RuntimeException("location not set")
