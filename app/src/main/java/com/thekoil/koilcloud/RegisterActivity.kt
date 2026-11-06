package com.thekoil.koilcloud

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.auth.AuthError
import com.thekoil.koilcloud.auth.AuthManager
import com.thekoil.koilcloud.auth.AuthResult
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val loginField = findViewById<EditText>(R.id.registerLogin)
        val password = findViewById<EditText>(R.id.registerPassword)
        val invite = findViewById<EditText>(R.id.registerInvite)
        val button = findViewById<Button>(R.id.registerButton)
        val backToLogin = findViewById<TextView>(R.id.backToLogin)

        val loginError = findViewById<TextView>(R.id.registerLoginError)
        val passwordError = findViewById<TextView>(R.id.registerPasswordError)
        val inviteError = findViewById<TextView>(R.id.registerInviteError)

        backToLogin.setOnClickListener { finish() }

        button.setOnClickListener {
            loginError.visibility = View.GONE
            passwordError.visibility = View.GONE
            inviteError.visibility = View.GONE

            val l = loginField.text.toString().trim()
            val p = password.text.toString()
            val code = invite.text.toString().trim()

            var valid = true
            if (l.isBlank()) {
                loginError.text = "Введите логин"
                loginError.visibility = View.VISIBLE
                valid = false
            }
            if (p.length < 4) {
                passwordError.text = "Пароль минимум 4 символа"
                passwordError.visibility = View.VISIBLE
                valid = false
            }
            if (code.isBlank()) {
                inviteError.text = "Введите пригласительный код"
                inviteError.visibility = View.VISIBLE
                valid = false
            }
            if (!valid) return@setOnClickListener

            button.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.register(l, p, code)
                button.isEnabled = true

                when (result) {
                    is AuthResult.Success -> {
                        AuthManager.save(this@RegisterActivity, result.token, l)
                        startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                        finishAffinity()
                    }
                    is AuthResult.Failure -> {
                        when (result.error) {
                            AuthError.LOGIN_TAKEN -> {
                                loginError.text = "Такой логин уже занят"
                                loginError.visibility = View.VISIBLE
                            }
                            AuthError.INVALID_INVITE -> {
                                inviteError.text = "Неверный пригласительный код"
                                inviteError.visibility = View.VISIBLE
                            }
                            AuthError.INVALID_CREDENTIALS -> {
                                loginError.text = "Неправильный логин или пароль"
                                loginError.visibility = View.VISIBLE
                            }
                            AuthError.NETWORK -> {
                                Toast.makeText(this@RegisterActivity, "Нет соединения", Toast.LENGTH_SHORT).show()
                            }
                            AuthError.UNKNOWN -> {
                                Toast.makeText(this@RegisterActivity, "Ошибка регистрации", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}