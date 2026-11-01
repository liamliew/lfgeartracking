package com.lfcreative.geartracker.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

object LoginForm {
    /** Returns an error message, or null when the form can be submitted. */
    fun validate(email: String, password: String): String? = when {
        email.isBlank() -> "Enter your email"
        !email.trim().contains('@') -> "That does not look like an email address"
        password.isEmpty() -> "Enter your password"
        else -> null
    }
}

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = _state.value
        if (current.loading) return
        LoginForm.validate(current.email, current.password)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = auth.signIn(current.email, current.password)
            // On success the auth state flips and navigation leaves this screen.
            _state.update {
                it.copy(
                    loading = false,
                    password = if (result.isSuccess) "" else it.password,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }
}
