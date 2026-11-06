package com.thekoil.koilcloud.model

data class AuthResponse(
    val token: String? = null,
    val error: String? = null,   // "invalid_credentials", "login_taken", "invalid_invite"
    val message: String? = null  // человекочитаемое сообщение (опционально)
)