package com.lfcreative.geartracker

import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import com.lfcreative.geartracker.data.auth.SignInFailure
import com.lfcreative.geartracker.ui.login.LoginForm
import com.lfcreative.geartracker.ui.login.LoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAuth(var result: Result<Unit> = Result.success(Unit)) : AuthRepository {
    override val state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    var calls = 0
    override suspend fun signIn(email: String, password: String): Result<Unit> {
        calls++
        return result
    }
    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `form validation`() {
        assertEquals("Enter your email", LoginForm.validate(" ", "x"))
        assertEquals("That does not look like an email address", LoginForm.validate("nope", "x"))
        assertEquals("Enter your password", LoginForm.validate("a@b.co", ""))
        assertNull(LoginForm.validate("a@b.co", "secret"))
    }

    @Test fun `invalid form never calls the server`() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)
        vm.signIn()
        advanceUntilIdle()
        assertEquals(0, auth.calls)
        assertEquals("Enter your email", vm.state.value.error)
    }

    @Test fun `failed sign in shows the message and keeps the password`() = runTest(dispatcher) {
        val auth = FakeAuth(Result.failure(SignInFailure("Invalid email or password")))
        val vm = LoginViewModel(auth)
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("wrong")
        vm.signIn()
        advanceUntilIdle()
        assertEquals(1, auth.calls)
        assertEquals("Invalid email or password", vm.state.value.error)
        assertEquals("wrong", vm.state.value.password)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `successful sign in clears the password`() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth())
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("secret")
        vm.signIn()
        advanceUntilIdle()
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.error)
        assertTrue(!vm.state.value.loading)
    }
}
