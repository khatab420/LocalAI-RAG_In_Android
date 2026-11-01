package com.example.localai

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.localai.ui.ChatScreen
import com.example.localai.ui.ChatViewModel
import com.example.localai.ui.HistoryScreen
import com.example.localai.ui.ModelsScreen
import com.example.localai.ui.KnowledgeScreen
import com.example.localai.ui.ModelsViewModel
import com.example.localai.ui.RagViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LocalAiApp).container

        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }

            // notifications permission for the download notification (Android 13+)
            val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            MaterialTheme(colorScheme = colors) {
                Surface {
                    val nav = rememberNavController()
                    val modelsVm: ModelsViewModel =
                        viewModel(factory = viewModelFactory { initializer { ModelsViewModel(container) } })
                    val chatVm: ChatViewModel =
                        viewModel(factory = viewModelFactory { initializer { ChatViewModel(container) } })

                    val ragVm: RagViewModel =
                        viewModel(factory = viewModelFactory { initializer { RagViewModel(container) } })

                    NavHost(nav, startDestination = "models") {
                        composable("models") {
                            ModelsScreen(
                                vm = modelsVm,
                                openChat = { nav.navigate("chat") },
                                openHistory = { nav.navigate("history") },
                                openDocs = { nav.navigate("docs") }
                            )
                        }
                        composable("docs") {
                            KnowledgeScreen(
                                vm = ragVm,
                                onBack = { nav.popBackStack() },
                                openModels = { nav.popBackStack("models", inclusive = false) }
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                vm = chatVm,
                                onBack = { nav.popBackStack() },
                                onOpenHistory = { nav.navigate("history") },
                                onOpenDocs = { nav.navigate("docs") }
                            )
                        }
                        composable("history") {
                            HistoryScreen(
                                vm = chatVm,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate("chat") }
                            )
                        }
                    }
                }
            }
        }
    }
}
