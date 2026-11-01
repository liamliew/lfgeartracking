package com.lfcreative.geartracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import com.lfcreative.geartracker.BuildConfig
import com.lfcreative.geartracker.ui.debug.DebugScanFab
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lfcreative.geartracker.data.auth.AuthState
import com.lfcreative.geartracker.ui.home.HomeScreen
import com.lfcreative.geartracker.ui.login.LoginScreen
import com.lfcreative.geartracker.ui.session.PackMoveScreen
import com.lfcreative.geartracker.ui.settings.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PACK_MOVE = "pack_move"
}

@Composable
fun AppNav(viewModel: MainViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()

    if (auth is AuthState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val navController = rememberNavController()
    val start = if (auth is AuthState.SignedIn) Routes.HOME else Routes.LOGIN

    // Follow sign-in / sign-out wherever the user is (including session expiry).
    LaunchedEffect(auth is AuthState.SignedIn) {
        val target = if (auth is AuthState.SignedIn) Routes.HOME else Routes.LOGIN
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) { popUpTo(0) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = start) {
            composable(Routes.LOGIN) { LoginScreen() }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenPackMove = { navController.navigate(Routes.PACK_MOVE) },
                )
            }
            composable(Routes.PACK_MOVE) { PackMoveScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
        }
        if (BuildConfig.DEBUG) {
            DebugScanFab(
                onScan = viewModel::submitScan,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
            )
        }
    }
}
