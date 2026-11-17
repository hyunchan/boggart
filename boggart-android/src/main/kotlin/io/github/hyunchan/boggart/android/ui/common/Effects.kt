package io.github.hyunchan.boggart.android.ui.common
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow

/** ViewModel의 일회성 효과를 화면에서 처리한다. 문구는 스낵바로, 닫기는 [onClose]로, 앱으로 돌아가기는 토스트를 띄우고 [onReturnToApp]으로. */
@Composable
internal fun CollectEffects(
    effects: Flow<UiEffect>,
    snackbar: SnackbarHostState,
    onClose: () -> Unit = {},
    onReturnToApp: () -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current.applicationContext
    val close by rememberUpdatedState(onClose)
    val returnToApp by rememberUpdatedState(onReturnToApp)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is UiEffect.Message -> snackbar.showSnackbar(effect.message.format(resources))
                UiEffect.Close -> close()
                is UiEffect.ReturnToApp -> {
                    // 화면이 곧 닫히므로 앱 위에서도 보이는 토스트로 알린다.
                    Toast.makeText(context, effect.message.format(resources), Toast.LENGTH_LONG).show()
                    returnToApp()
                }
            }
        }
    }
}

internal fun UiMessage.format(resources: Resources): String = resources.getString(res, *args.toTypedArray())
