package com.thekoil.koilcloud

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.auth.AuthError
import com.thekoil.koilcloud.auth.AuthManager
import com.thekoil.koilcloud.auth.AuthResult
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApiClient.init(applicationContext)

        setContentView(R.layout.activity_register)

        val loginField = findViewById<EditText>(R.id.registerLogin)
        val password = findViewById<EditText>(R.id.registerPassword)
        val invite = findViewById<EditText>(R.id.registerInvite)
        val button = findViewById<Button>(R.id.registerButton)
        val backToLogin = findViewById<TextView>(R.id.backToLogin)

        val loginError = findViewById<TextView>(R.id.registerLoginError)
        val passwordError = findViewById<TextView>(R.id.registerPasswordError)
        val inviteError = findViewById<TextView>(R.id.registerInviteError)

        // Скрываем ошибку при вводе
        loginField.doAfterTextChanged { hideError(loginField, loginError) }
        password.doAfterTextChanged { hideError(password, passwordError) }
        invite.doAfterTextChanged { hideError(invite, inviteError) }

        backToLogin.setOnClickListener { finish() }

        button.setOnClickListener {
            hideError(loginField, loginError)
            hideError(password, passwordError)
            hideError(invite, inviteError)

            val l = loginField.text.toString().trim()
            val p = password.text.toString()
            val code = invite.text.toString().trim()

            var valid = true
            if (l.isBlank()) {
                showError(loginField, loginError, "Введите логин")
                valid = false
            }
            if (p.length < 4) {
                showError(password, passwordError, "Пароль минимум 4 символа")
                valid = false
            }
            if (code.isBlank()) {
                showError(invite, inviteError, "Введите пригласительный код")
                valid = false
            }
            if (!valid) return@setOnClickListener

            button.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.register(l, p, code)
                button.isEnabled = true

                when (result) {
                    is AuthResult.Success -> {
                        AuthManager.save(
                            context = this@RegisterActivity,
                            access = result.accessToken,
                            refresh = result.refreshToken
                        )
                        startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                        finishAffinity()
                    }
                    is AuthResult.Failure -> {
                        when (result.error) {
                            AuthError.LOGIN_TAKEN ->
                                showError(loginField, loginError, "Такой логин уже занят")
                            AuthError.INVALID_INVITE ->
                                showError(invite, inviteError, "Неверный пригласительный код")
                            AuthError.INVALID_CREDENTIALS ->
                                showError(loginField, loginError, "Неправильный логин или пароль")
                            AuthError.UNAUTHORIZED ->
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Сессия истекла, войдите снова",
                                    Toast.LENGTH_SHORT
                                ).show()
                            AuthError.NETWORK ->
                                Toast.makeText(this@RegisterActivity, "Нет соединения", Toast.LENGTH_SHORT).show()
                            AuthError.UNKNOWN ->
                                Toast.makeText(this@RegisterActivity, "Ошибка регистрации", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showError(field: EditText, errorView: TextView, message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
        field.setBackgroundResource(R.drawable.bg_input_error)
    }

    private fun hideError(field: EditText, errorView: TextView) {
        errorView.visibility = View.GONE
        field.setBackgroundResource(R.drawable.bg_input)
    }
}