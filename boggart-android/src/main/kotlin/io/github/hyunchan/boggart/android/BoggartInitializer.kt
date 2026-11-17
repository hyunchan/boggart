package io.github.hyunchan.boggart.android

import android.content.Context
import androidx.startup.Initializer

/**
 * 앱의 `Application.onCreate()`보다 먼저 [BoggartAndroid]를 기본 설정으로 설치한다(Jetpack App Startup).
 * 의존성만 넣으면 동작하고, HTTP 클라이언트보다 늦게 설치될 일이 없다.
 *
 * 설정은 [BoggartAndroid.configure]로 바꾼다. 자동 설치를 끄려면 앱 매니페스트에서 이 항목을
 * `tools:node="remove"`로 지우고 [BoggartAndroid.install]을 직접 부른다.
 */
public class BoggartInitializer : Initializer<BoggartAndroid> {
    override fun create(context: Context): BoggartAndroid {
        BoggartAndroid.install(context)
        return BoggartAndroid
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
