package io.github.hyunchan.boggart.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.control.ControlServer
import io.github.hyunchan.boggart.android.control.PresetControlStore
import io.github.hyunchan.boggart.android.control.SavedControlStore
import io.github.hyunchan.boggart.android.overlay.OverlayController
import io.github.hyunchan.boggart.control.BoggartControl
import io.github.hyunchan.boggart.control.BoggartMcp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 안드로이드 설치 진입점. 프리셋 규칙·저장 규칙·에셋 바디 해석을 [Boggart]에 등록한다.
 *
 * 기본 설정으로는 [BoggartInitializer]가 앱 시작 때 자동으로 설치하므로 앱 코드가 필요 없다.
 * 설정을 바꾸려면 [configure]를 부른다.
 *
 * ```
 * class DebugApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         BoggartAndroid.configure { overlay = false }
 *     }
 * }
 * ```
 */
public object BoggartAndroid {
    public const val DEFAULT_ASSETS_DIR: String = "boggart"

    /** `filesDir` 아래 Boggart 전용 디렉토리 이름. */
    internal const val FILES_DIR = "boggart"
    internal const val PRESET_OVERRIDES_FILE = "preset-overrides.json"
    internal const val SAVED_RULES_FILE = "saved-rules.json"
    private const val TAG = "Boggart"
    private const val CONTROL_SOCKET_PREFIX = "boggart-"

    public class Config internal constructor() {
        /** 에셋 디렉토리. `assets/<assetsDir>/rules.json`, `assets/<assetsDir>/bodies/`. */
        public var assetsDir: String = DEFAULT_ASSETS_DIR

        /** false면 프리셋 규칙을 등록하지 않는다. 바디 해석기는 저장 규칙도 쓰므로 항상 등록한다. */
        public var loadAssets: Boolean = true

        /** false면 저장 규칙을 쓰지 않고, 프리셋 enabled 토글도 메모리에만 둔다. */
        public var persistence: Boolean = true

        /** null이면 현재 모드를 유지한다. */
        public var mode: Mode? = null

        /** false면 화면 위 버블을 띄우지 않는다. 시스템 오버레이 권한은 쓰지 않는다. */
        public var overlay: Boolean = true

        /** 버블의 호출 수집 창 길이(ms). 창이 닫힐 때 1건이면 그 교환으로, 2건 이상이면 목록으로 진입한다. */
        public var overlayWindowMs: Long = 2_000

        /**
         * 버블을 붙일 화면을 고른다. false를 돌려주는 Activity에는 버블을 붙이지 않는다. 기본은 모든 화면.
         * 로그인·결제 SDK처럼 앱 밖의 화면을 빼려면 앱 코드의 패키지로 거른다.
         *
         * ```
         * overlayFilter = { it.javaClass.name.startsWith("com.example.") }
         * ```
         *
         * 버블만 빠질 뿐 그 화면에서 나간 호출의 기록과 원격 제어는 그대로다.
         */
        public var overlayFilter: (Activity) -> Boolean = { true }

        /**
         * false면 원격 제어를 열지 않는다. 켜져 있으면 `adb forward tcp:8765 localabstract:boggart-<applicationId>`로
         * 연결해 호출 확인·규칙 편집·모드 변경을 HTTP(JSON)로 할 수 있다. adb shell과 이 앱의 연결만 받는다.
         */
        public var remoteControl: Boolean = true
    }

    private class Installation(
        val scope: CoroutineScope,
        val presetRules: PresetRuleSource?,
        val savedRules: SavedRuleSource?,
        val loading: List<Deferred<Unit>>,
    )

    @Volatile
    private var installation: Installation? = null

    /**
     * [configure]가 다시 설치할 때 쓰는 앱. 프로세스와 수명이 같은 Application만 보관한다.
     * 타입을 Context로 두면 Activity가 들어올 수 있다고 보고 린트가 StaticFieldLeak로 경고한다.
     */
    @Volatile
    private var installedApp: Application? = null

    /**
     * 설치된 Boggart의 설정을 바꾼다. 자동 설치 뒤 `Application.onCreate()`에서 부르면 된다.
     * 바꾸지 않은 항목은 기본값으로 돌아간다(이전 [configure]의 값을 이어받지 않는다).
     *
     * @throws IllegalStateException 자동 설치를 끄고 아직 [install]을 부르지 않았을 때
     */
    public fun configure(block: Config.() -> Unit) {
        val context = checkNotNull(installedApp) {
            "Boggart isn't installed. Automatic installation is turned off, so call BoggartAndroid.install(context) instead."
        }
        install(context, block)
    }

    @Volatile
    private var overlayController: OverlayController? = null

    @Volatile
    private var controlServer: ControlServer? = null

    /** 프리셋 규칙. 설치 전이거나 `loadAssets = false`면 null. */
    public val presetRules: PresetRuleSource? get() = installation?.presetRules

    /** 저장 규칙. 설치 전이거나 `persistence = false`면 null. */
    public val savedRules: SavedRuleSource? get() = installation?.savedRules

    /**
     * 설치한다. 자동 설치를 쓰면 부를 필요가 없다. 다시 부르면 이전 설치를 새 설정으로 바꾼다.
     * 동기 반환하며 파일 로딩은 내부 IO 스코프에서 시작한다([awaitLoaded]로 대기). [context]는 applicationContext만 보관한다.
     */
    public fun install(context: Context, block: Config.() -> Unit = {}) {
        val app = context.applicationContext
        installedApp = app as? Application
        val config = Config().apply(block)
        install(
            openAsset = { path ->
                try {
                    app.assets.open(path)
                } catch (_: FileNotFoundException) {
                    null
                }
            },
            filesDir = app.filesDir,
            logger = { message, error -> Log.w(TAG, message, error) },
            info = { message -> Log.i(TAG, message) },
            config = config,
        )
        synchronized(this) {
            overlayController?.stop()
            overlayController = (app as? Application)?.takeIf { config.overlay }?.let { OverlayController(it, config.overlayWindowMs, config.overlayFilter).apply { start() } }
            controlServer?.stop()
            controlServer = if (config.remoteControl) startControlServer(app.packageName) else null
        }
    }

    internal fun install(
        openAsset: (String) -> InputStream?,
        filesDir: File,
        logger: (String, Throwable) -> Unit,
        config: Config,
        info: (String) -> Unit = {},
    ) {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        val dir = File(filesDir, FILES_DIR)
        val presetRules = if (config.loadAssets) {
            PresetRuleSource(openAsset, File(dir, PRESET_OVERRIDES_FILE).takeIf { config.persistence }, config.assetsDir)
        } else {
            null
        }
        val savedRules = if (config.persistence) SavedRuleSource(File(dir, SAVED_RULES_FILE)) else null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        synchronized(this) {
            val previous = installation
            previous?.scope?.cancel()
            Boggart.configure {
                ruleSources.removeAll { it === previous?.presetRules || it === previous?.savedRules }
                presetRules?.let(::ruleSource)
                savedRules?.let(::ruleSource)
                bodyFileResolver = PresetBodyFileResolver(openAsset, config.assetsDir)
                config.mode?.let { mode = it }
            }
            val loading = listOfNotNull(
                presetRules?.let { source -> scope.async { source.load() } },
                savedRules?.let { source -> scope.async { source.load() } },
            )
            loading.forEach { job ->
                job.invokeOnCompletion { error ->
                    if (error != null && error !is CancellationException) logger("Failed to load Boggart rules", error)
                }
            }
            installation = Installation(scope, presetRules, savedRules, loading)
            scope.launch {
                runCatching { loading.awaitAll() }
                info("Rules loaded in ${elapsedMs()} ms")
            }
            Boggart.engine.onRulesWait = { waitedMs, ready ->
                info(if (ready) "A request waited $waitedMs ms for rules to load" else "A request went ahead after waiting $waitedMs ms; rules weren't loaded yet")
            }
        }
    }

    private fun startControlServer(packageName: String): ControlServer? {
        val socketName = "$CONTROL_SOCKET_PREFIX$packageName"
        val control = BoggartControl(
            savedRules = { savedRules?.let(::SavedControlStore) },
            presetRules = { presetRules?.let(::PresetControlStore) },
            info = { mapOf("packageName" to packageName, "socket" to "localabstract:$socketName") },
        )
        val server = ControlServer(socketName, control, BoggartMcp(control)) { message, error -> Log.w(TAG, message, error) }
        if (!server.start()) return null
        Log.i(TAG, "Remote control: adb forward tcp:8765 localabstract:$socketName && curl localhost:8765/ (MCP: http://localhost:8765/mcp)")
        return server
    }

    /** 현재 설치의 로딩이 끝날 때까지 기다린다. 로딩이 실패했으면 그 예외를 던진다. 설치 전이면 바로 반환한다. */
    public suspend fun awaitLoaded() {
        installation?.loading?.awaitAll()
    }

    /** 임시 규칙 [rule]을 저장 규칙으로 승격하고 임시 규칙에서 제거한다. */
    public suspend fun promote(rule: Rule) {
        val saved = checkNotNull(savedRules) { "BoggartAndroid is not installed with persistence" }
        saved.add(rule)
        Boggart.temporaryRules.remove(rule.id)
    }

    /** Boggart 화면(교환 목록)을 연다. Activity가 아닌 Context면 새 태스크로 연다. */
    public fun openUi(context: Context) {
        context.startActivity(BoggartActivity.intent(context))
    }

    /** 저장 규칙 [ruleId]를 제거한다. 없거나 저장을 쓰지 않으면 false. */
    public suspend fun demote(ruleId: String): Boolean = savedRules?.remove(ruleId) ?: false
}
