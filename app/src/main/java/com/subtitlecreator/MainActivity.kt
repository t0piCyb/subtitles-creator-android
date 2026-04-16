package com.subtitlecreator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subtitlecreator.ui.AppViewModel
import com.subtitlecreator.ui.screens.EditorScreen
import com.subtitlecreator.ui.screens.ExportScreen
import com.subtitlecreator.ui.screens.HomeScreen
import com.subtitlecreator.ui.screens.TranscribingScreen
import com.subtitlecreator.ui.theme.SubtitlesCreatorTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing — we want the foreground service regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifPermissionIfNeeded()
        setContent {
            SubtitlesCreatorTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNav(vm)
                }
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val TRANSCRIBING = "transcribing"
    const val EDITOR = "editor"
    const val EXPORT = "export"
}

@androidx.compose.runtime.Composable
fun AppNav(vm: AppViewModel) {
    val nav: NavHostController = rememberNavController()
    val state by vm.state.collectAsState()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(state = state, vm = vm, onStartTranscribe = {
                vm.startTranscription()
                nav.navigate(Routes.TRANSCRIBING)
            })
        }
        composable(Routes.TRANSCRIBING) {
            TranscribingScreen(state = state, onDone = {
                nav.navigate(Routes.EDITOR) {
                    popUpTo(Routes.HOME) { inclusive = false }
                }
            })
        }
        composable(Routes.EDITOR) {
            EditorScreen(state = state, vm = vm, onExport = {
                vm.export()
                nav.navigate(Routes.EXPORT)
            })
        }
        composable(Routes.EXPORT) {
            ExportScreen(state = state, onDone = {
                nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
            })
        }
    }
}
