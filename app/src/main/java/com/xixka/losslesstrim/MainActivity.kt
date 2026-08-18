package com.xixka.losslesstrim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixka.losslesstrim.data.VideoEntry
import com.xixka.losslesstrim.trim.QueueUi
import com.xixka.losslesstrim.trim.TrimController
import com.xixka.losslesstrim.ui.AnalysisScreen
import com.xixka.losslesstrim.ui.AppViewModel
import com.xixka.losslesstrim.ui.HomeScreen
import com.xixka.losslesstrim.ui.ProcessingScreen
import com.xixka.losslesstrim.ui.ResultScreen
import com.xixka.losslesstrim.ui.SettingsScreen
import com.xixka.losslesstrim.ui.theme.LosslessTrimTheme

sealed interface Screen {
    data object Home : Screen
    data class Analysis(val entry: VideoEntry) : Screen
    data object Processing : Screen
    data object Result : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LosslessTrimTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val vm: AppViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val queueUi by TrimController.queueUi.collectAsState()

    // 处理完成自动跳转结果页
    LaunchedEffect(queueUi) {
        if (queueUi is QueueUi.Finished && screen == Screen.Processing) {
            screen = Screen.Result
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        // 从结果页返回必须重扫：覆盖模式下原文件 URI 已失效（删旧建新），列表需刷新
        if (screen == Screen.Result) vm.rescan()
        screen = Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm,
            onOpenAnalysis = { screen = Screen.Analysis(it) },
            onOpenSettings = { screen = Screen.Settings },
            onStartProcessing = {
                vm.clearResults()
                screen = Screen.Processing
            },
        )

        is Screen.Analysis -> AnalysisScreen(vm, s.entry) { screen = Screen.Home }

        Screen.Processing -> ProcessingScreen()

        Screen.Result -> ResultScreen(
            vm = vm,
            onBackHome = {
                vm.rescan()
                screen = Screen.Home
            },
            onRetry = {
                vm.retryFailed()
                screen = Screen.Processing
            },
        )

        Screen.Settings -> SettingsScreen(vm) { screen = Screen.Home }
    }
}
