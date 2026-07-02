package com.kindred.api.photo

class InvalidStorageKeyException(message: String) : RuntimeException(message)

class PhotoLimitReachedException(limit: Int) : RuntimeException("photo limit reached ($limit)")

class PhotoNotFoundException : RuntimeException("photo not found")
