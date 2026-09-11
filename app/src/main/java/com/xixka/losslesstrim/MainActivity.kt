package com.xixka.losslesstrim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/** 导航落盘用的轻量标签（[Screen.Analysis] 的 entry 含 probe 大对象，只存 docUri 重建后找回） */
private enum class ScreenTag { HOME, ANALYSIS, PROCESSING, RESULT, SETTINGS }

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
    val files by vm.files.collectAsState()
    // 导航状态配 rememberSaveable（M3）：Activity 重建（旋转/系统深色切换/
    // 后台回收恢复）不再静默回首页。Analysis 屏按 docUri 从 files 里找回
    // 条目；找不到（进程被杀后列表尚未恢复/文件已被覆盖重扫）回首页。
    // 队列本身仍是进程内存态：前台服务存活期间 TrimController.queueUi 保持
    // 推进，重建后 Processing/Result 屏可恢复展示；仅进程整体死亡会丢队列
    // （个人工具取舍：DataStore 落盘收益低于 TrimJob 全量序列化复杂度）
    var screenTag by rememberSaveable { mutableStateOf(ScreenTag.HOME.name) }
    var analysisDocUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun navigate(tag: ScreenTag, uri: String? = null) {
        screenTag = tag.name
        analysisDocUri = if (tag == ScreenTag.ANALYSIS) uri else null
    }

    val tag = runCatching { ScreenTag.valueOf(screenTag) }.getOrDefault(ScreenTag.HOME)
    val screen: Screen = when (tag) {
        ScreenTag.ANALYSIS ->
            files.firstOrNull { it.docUri.toString() == analysisDocUri }
                ?.let { Screen.Analysis(it) } ?: Screen.Home
        ScreenTag.PROCESSING -> Screen.Processing
        ScreenTag.RESULT -> Screen.Result
        ScreenTag.SETTINGS -> Screen.Settings
        ScreenTag.HOME -> Screen.Home
    }

    val queueUi by TrimController.queueUi.collectAsState()
    // 首页列表滚动状态提升到 App 层：页面切换时 HomeScreen 退出组合，
    // 状态放这里才能跨切换存活，返回时恢复上次位置
    val homeListState = rememberLazyListState()
    val treeUri by vm.treeUri.collectAsState()
    LaunchedEffect(treeUri) {
        // 换目录/切单文件模式：旧位置对新列表无意义，回顶
        homeListState.scrollToItem(0)
    }

    // 处理完成自动跳转结果页
    LaunchedEffect(queueUi) {
        if (queueUi is QueueUi.Finished && screen == Screen.Processing) {
            navigate(ScreenTag.RESULT)
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        // 从结果页返回必须重扫：覆盖模式下原文件 URI 已失效（删旧建新），列表需刷新
        if (screen == Screen.Result) vm.rescan()
        navigate(ScreenTag.HOME)
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm,
            listState = homeListState,
            onOpenAnalysis = { navigate(ScreenTag.ANALYSIS, it.docUri.toString()) },
            onOpenSettings = { navigate(ScreenTag.SETTINGS) },
            onStartProcessing = {
                vm.clearResults()
                navigate(ScreenTag.PROCESSING)
            },
            onShowResult = { navigate(ScreenTag.RESULT) },
        )

        is Screen.Analysis -> AnalysisScreen(vm, s.entry) { navigate(ScreenTag.HOME) }

        Screen.Processing -> ProcessingScreen(onShowResult = { navigate(ScreenTag.RESULT) })

        Screen.Result -> ResultScreen(
            vm = vm,
            onBackHome = {
                vm.rescan()
                navigate(ScreenTag.HOME)
            },
            onRetry = {
                // 没有真正可重试的任务时留在结果页，避免进入空的"队列未在运行"页
                if (vm.retryFailed()) navigate(ScreenTag.PROCESSING)
            },
        )

        Screen.Settings -> SettingsScreen(vm) { navigate(ScreenTag.HOME) }
    }
}
