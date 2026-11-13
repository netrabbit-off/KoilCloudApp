package com.thekoil.koilcloud.model

import com.google.gson.annotations.SerializedName

data class User(
    @SerializedName("id") val id: String? = null,
    @SerializedName("login") val login: String? = null
)