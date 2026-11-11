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

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApiClient.init(applicationContext)

        setContentView(R.layout.activity_login)

        if (AuthManager.isLoggedIn(this)) {
            goToMain()
            return
        }

        val loginField = findViewById<EditText>(R.id.loginField)
        val password = findViewById<EditText>(R.id.loginPassword)
        val button = findViewById<Button>(R.id.loginButton)
        val loginError = findViewById<TextView>(R.id.loginError)
        val passwordError = findViewById<TextView>(R.id.loginPasswordError)

        loginField.doAfterTextChanged { hideError(loginError) }
        password.doAfterTextChanged { hideError(passwordError) }

        findViewById<TextView>(R.id.goToRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        button.setOnClickListener {
            val l = loginField.text.toString().trim()
            val p = password.text.toString()

            hideError(loginError)
            hideError(passwordError)

            var valid = true
            if (l.isBlank()) {
                showError(loginError, "Введите логин")
                valid = false
            }
            if (p.isBlank()) {
                showError(passwordError, "Введите пароль")
                valid = false
            }
            if (!valid) return@setOnClickListener

            button.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.login(l, p)
                button.isEnabled = true

                when (result) {
                    is AuthResult.Success -> {
                        AuthManager.save(
                            context = this@LoginActivity,
                            access = result.accessToken,
                            refresh = result.refreshToken,
                            login = l,
                            userId = result.userId
                        )
                        goToMain()
                    }
                    is AuthResult.Failure -> {
                        when (result.error) {
                            AuthError.INVALID_CREDENTIALS ->
                                showError(loginError, "Неправильный логин или пароль")
                            AuthError.LOGIN_TAKEN ->
                                showError(loginError, "Такой логин уже занят")
                            AuthError.INVALID_INVITE ->
                                showError(loginError, "Неверный пригласительный код")
                            AuthError.UNAUTHORIZED ->
                                showError(loginError, "Сессия истекла, войдите снова")
                            AuthError.NETWORK ->
                                Toast.makeText(this@LoginActivity, "Нет соединения", Toast.LENGTH_SHORT).show()
                            AuthError.UNKNOWN ->
                                showError(loginError, "Что-то пошло не так")
                        }
                    }
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
    }

    private fun hideError(view: TextView) {
        view.visibility = View.GONE
    }
}