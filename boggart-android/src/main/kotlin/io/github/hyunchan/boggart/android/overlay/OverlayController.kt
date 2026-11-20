package io.github.hyunchan.boggart.android.overlay
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.android.BoggartActivity
import io.github.hyunchan.boggart.android.Screen
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 시스템 오버레이 권한 없이, 화면에 올라온 Activity의 DecorView에 버블(ComposeView)을 붙인다.
 *
 * Resume 때 붙이고 Pause 때 뗀다. [BoggartActivity]와 `ComponentActivity`가 아닌 Activity에는 붙이지 않는다.
 * 버블 위치는 Activity가 바뀌어도 유지된다.
 */
internal class OverlayController(
    private val application: Application,
    windowMs: Long,
) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = OverlayCoordinator(clock = System::currentTimeMillis, windowMs = windowMs)
    val position = mutableStateOf<Offset?>(null)
    private val attached = HashMap<Activity, View>()

    fun start() {
        coordinator.skipExisting(Boggart.exchangeLog.exchanges.value)
        scope.launch {
            Boggart.exchangeLog.exchanges.collect { exchanges ->
                coordinator.onExchanges(exchanges).forEach { after ->
                    launch {
                        delay(after)
                        coordinator.tick()
                    }
                }
            }
        }
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        attached.keys.toList().forEach(::detach)
        scope.cancel()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BoggartActivity || activity !is ComponentActivity || activity in attached) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BoggartTheme {
                    OverlayBubble(
                        coordinator = coordinator,
                        position = position.value,
                        onPositionChange = { position.value = it },
                        onOpen = { target -> open(activity, target) },
                        onOpenRules = { activity.startActivity(BoggartActivity.intent(activity, Screen.Rules)) },
                    )
                }
            }
        }
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attached[activity] = view
    }

    override fun onActivityPaused(activity: Activity) {
        detach(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        detach(activity)
    }

    private fun detach(activity: Activity) {
        val view = attached.remove(activity) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun open(activity: Activity, target: OverlayCoordinator.Target?) {
        val screen = when (target) {
            is OverlayCoordinator.Target.Single -> Screen.CallDetail(target.exchangeId)
            OverlayCoordinator.Target.List, null -> Screen.Calls
        }
        coordinator.dismiss()
        activity.startActivity(BoggartActivity.intent(activity, screen))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit
    override fun onActivityStarted(activity: Activity): Unit = Unit
    override fun onActivityStopped(activity: Activity): Unit = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit
}
