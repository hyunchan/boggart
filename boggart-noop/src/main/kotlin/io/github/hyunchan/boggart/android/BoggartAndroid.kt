package io.github.hyunchan.boggart.android

import android.content.Context
import io.github.hyunchan.boggart.Mode

/**
 * 릴리스 빌드용 no-op. boggart-android와 같은 FQCN·시그니처를 제공해 호스트 코드를 고치지 않고 컴파일되게 한다.
 *
 * core 타입(`Rule`, `PresetRuleSource`, `SavedRuleSource`)을 쓰는 `promote`·`presetRules`·`savedRules`는 제공하지 않는다.
 */
public object BoggartAndroid {
    public const val DEFAULT_ASSETS_DIR: String = "boggart"

    public class Config internal constructor() {
        public var assetsDir: String = DEFAULT_ASSETS_DIR
        public var loadAssets: Boolean = true
        public var persistence: Boolean = true
        public var mode: Mode? = null
        public var overlay: Boolean = true
        public var overlayWindowMs: Long = 2_000
    }

    /** 설정 블록은 실행하지만 아무것도 등록하지 않는다. */
    @Suppress("UNUSED_PARAMETER")
    public fun install(context: Context, block: Config.() -> Unit = {}) {
        Config().apply(block)
    }

    /** 설정 블록은 실행하지만 아무것도 바꾸지 않는다. */
    public fun configure(block: Config.() -> Unit) {
        Config().apply(block)
    }

    @Suppress("UNUSED_PARAMETER")
    public fun openUi(context: Context): Unit = Unit

    public suspend fun awaitLoaded(): Unit = Unit

    @Suppress("UNUSED_PARAMETER")
    public suspend fun demote(ruleId: String): Boolean = false
}
