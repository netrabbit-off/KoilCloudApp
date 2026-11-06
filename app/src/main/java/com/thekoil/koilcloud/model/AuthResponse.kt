package com.thekoil.koilcloud.model

data class AuthResponse(
    val token: String? = null,
    val error: String? = null,
    val message: String? = null
)