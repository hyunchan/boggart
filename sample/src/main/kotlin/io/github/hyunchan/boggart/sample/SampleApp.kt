package io.github.hyunchan.boggart.sample

import android.app.Application
import io.github.hyunchan.boggart.android.BoggartAndroid

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // release 변형에서는 boggart-noop이 같은 API를 무동작으로 제공한다.
        BoggartAndroid.install(this)
    }
}
