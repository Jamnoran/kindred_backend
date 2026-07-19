package com.kindred.api.discovery

class CannotReactToSelfException : RuntimeException("you cannot react to your own profile")

class AlreadyReactedException : RuntimeException("you already reacted to this profile")

class ReactionTargetNotFoundException : RuntimeException("profile not found")
