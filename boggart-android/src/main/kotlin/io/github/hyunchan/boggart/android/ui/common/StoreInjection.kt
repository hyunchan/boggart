package io.github.hyunchan.boggart.android.ui.common
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 화면들이 쓰는 [BoggartStore]. 조립 지점(`BoggartActivity`)이 한 번 정해 내려 주므로 화면 코드는 구현을 모른다.
 * 내려 주지 않고 화면을 그리면 조립이 빠진 것이므로 바로 실패한다.
 */
internal val LocalBoggartStore: ProvidableCompositionLocal<BoggartStore> =
    staticCompositionLocalOf { error("BoggartStore is not provided. Wrap the screens with CompositionLocalProvider(LocalBoggartStore provides ...).") }

/** 지금 내비게이션 항목의 ViewModel을 [LocalBoggartStore]로 만든다. 화면 composable의 기본 인자로 쓴다. */
@Composable
internal inline fun <reified VM : ViewModel> storeViewModel(crossinline create: (BoggartStore) -> VM): VM {
    val store = LocalBoggartStore.current
    return viewModel { create(store) }
}
