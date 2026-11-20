package com.watchlink.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchlink.wear.NavRequest
import com.watchlink.wear.PhoneMessenger
import kotlinx.coroutines.delay

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail"
    const val REPLY = "reply"
    const val SETTINGS = "settings"
}

@Composable
fun WatchApp(
    navRequest: NavRequest?,
    onNavHandled: () -> Unit,
    viewModel: WatchViewModel = viewModel(),
) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            PhoneMessenger.refreshConnection(context)
            delay(20_000)
        }
    }

    LaunchedEffect(navRequest) {
        val request = navRequest ?: return@LaunchedEffect
        viewModel.selectedKey = request.key
        navController.popBackStack(Routes.HOME, false)
        navController.navigate(Routes.DETAIL)
        if (request.reply) navController.navigate(Routes.REPLY)
        onNavHandled()
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNotification = { key ->
                    viewModel.selectedKey = key
                    navController.navigate(Routes.DETAIL)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DETAIL) {
            DetailScreen(
                notificationKey = viewModel.selectedKey,
                onReply = { navController.navigate(Routes.REPLY) },
                onClose = { navController.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.REPLY) {
            ReplyScreen(
                notificationKey = viewModel.selectedKey,
                onCancel = { navController.popBackStack() },
                onSent = { navController.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}
