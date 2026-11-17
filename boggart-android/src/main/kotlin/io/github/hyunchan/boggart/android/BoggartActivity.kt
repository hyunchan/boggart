package io.github.hyunchan.boggart.android
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.hyunchan.boggart.android.ui.calldetail.CallDetailScreen
import io.github.hyunchan.boggart.android.ui.calllist.CallListScreen
import io.github.hyunchan.boggart.android.ui.common.BoggartStore
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import io.github.hyunchan.boggart.android.ui.common.GlobalBoggartStore
import io.github.hyunchan.boggart.android.ui.common.LocalBoggartStore
import io.github.hyunchan.boggart.android.ui.ruleedit.RuleEditScreen
import io.github.hyunchan.boggart.android.ui.rulelist.RuleListScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Boggart 화면. 호출 목록 · 호출 상세/편집 · 규칙 목록 · 규칙 편집을 내부 스택으로 오간다.
 * 화면이 쓰는 의존성을 조립하는 곳이기도 하다. 저장소 구현은 여기서만 정하고 화면에는 [LocalBoggartStore]로 내려 준다.
 */
public class BoggartActivity : ComponentActivity() {
    private val store: BoggartStore = GlobalBoggartStore

    /** 이미 열린 상태에서 새로 요청된 시작 스택. 가장 최근 요청 하나만 남긴다. */
    private val restarts = Channel<List<Screen>>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = startScreens(intent)
        setContent {
            CompositionLocalProvider(LocalBoggartStore provides store) {
                BoggartTheme { BoggartNavigation(start, restarts.receiveAsFlow(), onExit = ::finish) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 열린 상태에서 다시 요청되면(singleTop) 요청한 화면부터 새로 연다. recreate()는 저장된 이전 스택을
        // 되살려 요청을 무시하므로 쓰지 않고, 지금 스택을 새 시작 스택으로 바꾼다.
        setIntent(intent)
        restarts.trySend(startScreens(intent))
    }

    internal companion object {
        private const val EXTRA_SCREEN = "io.github.hyunchan.boggart.SCREEN"

        fun intent(context: Context, start: Screen = Screen.Calls): Intent =
            Intent(context, BoggartActivity::class.java).apply {
                putExtra(EXTRA_SCREEN, start.encode())
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        private fun startScreens(intent: Intent?): List<Screen> {
            val start = intent?.getStringExtra(EXTRA_SCREEN)?.let(Screen::decode) ?: Screen.Calls
            return if (start == Screen.Calls) listOf(Screen.Calls) else listOf(Screen.Calls, start)
        }
    }
}

/**
 * 내부 스택 내비게이션. 항목마다 ViewModel 저장소를 따로 두어(Navigation 3 데코레이터)
 * 화면을 다시 열면 새 ViewModel이, 스택에서 빠지면 그 ViewModel이 정리된다.
 */
@Composable
private fun BoggartNavigation(start: List<Screen>, restarts: Flow<List<Screen>>, onExit: () -> Unit) {
    val stack = rememberSaveable(
        saver = listSaver(save = { list -> list.map { it.encode() } }, restore = { saved -> saved.map(Screen::decode).toMutableStateList() }),
    ) { start.toMutableStateList() }
    LaunchedEffect(restarts) {
        restarts.collect { screens ->
            // 비우는 순간이 그려지지 않도록(빈 스택) 한 스냅숏 안에서 바꾼다.
            Snapshot.withMutableSnapshot {
                stack.clear()
                stack.addAll(screens)
            }
        }
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit()
    }

    NavDisplay(
        backStack = stack,
        onBack = ::pop,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Screen.Calls> {
                CallListScreen(onBack = ::pop, onOpenCall = { push(Screen.CallDetail(it)) }, onOpenRules = { push(Screen.Rules) })
            }
            entry<Screen.CallDetail> { screen ->
                CallDetailScreen(screen.id, onBack = ::pop, onOpenRule = { push(Screen.RuleEdit(it)) }, onReturnToApp = onExit)
            }
            entry<Screen.Rules> { RuleListScreen(onBack = ::pop, onOpenRule = { push(Screen.RuleEdit(it)) }) }
            entry<Screen.RuleEdit> { screen -> RuleEditScreen(screen.ruleId, onBack = ::pop) }
        },
    )
}
