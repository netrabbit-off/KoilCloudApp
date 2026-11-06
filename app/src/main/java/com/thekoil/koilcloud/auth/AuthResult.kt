package com.thekoil.koilcloud.auth

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Failure(val error: AuthError) : AuthResult()
}

enum class AuthError {
    INVALID_CREDENTIALS,   // Неправильный логин или пароль
    LOGIN_TAKEN,           // Такой логин уже занят
    INVALID_INVITE,        // Неверный пригласительный код
    NETWORK,               // Сеть недоступна
    UNKNOWN                // Всё остальное
}