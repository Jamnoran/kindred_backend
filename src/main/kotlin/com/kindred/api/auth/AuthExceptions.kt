package com.kindred.api.auth

class EmailAlreadyRegisteredException : RuntimeException("email is already registered")

class UnderageSignupException : RuntimeException("you must be at least 18 years old")

class InvalidVerificationTokenException : RuntimeException("invalid or expired verification token")

class EmailNotVerifiedException : RuntimeException("email address is not verified")

class AccountBannedException : RuntimeException("this account has been suspended")
