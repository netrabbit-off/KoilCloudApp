package com.thekoil.koilcloud.auth

class UnauthorizedException : Exception("Unauthorized: refresh token expired or missing")