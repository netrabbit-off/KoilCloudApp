package com.thekoil.koilcloud.auth

sealed class AuthResult {
    data class Success(val accessToken: String, val refreshToken: String, val userId: String?) : AuthResult()
    data class Failure(val error: AuthError) : AuthResult()
}

enum class AuthError {
    INVALID_CREDENTIALS,
    LOGIN_TAKEN,
    INVALID_INVITE,
    NETWORK,
    UNAUTHORIZED,
    UNKNOWN
}