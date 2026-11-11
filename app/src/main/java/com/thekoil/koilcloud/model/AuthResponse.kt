package com.thekoil.koilcloud.model

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null
)