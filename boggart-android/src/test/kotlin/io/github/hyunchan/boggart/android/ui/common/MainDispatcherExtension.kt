package io.github.hyunchan.boggart.android.ui.common
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** viewModelScope가 쓰는 Main 디스패처를 테스트 디스패처로 바꾼다. 쓰기와 효과가 호출 즉시 끝난다. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherExtension :
    BeforeEachCallback,
    AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}
