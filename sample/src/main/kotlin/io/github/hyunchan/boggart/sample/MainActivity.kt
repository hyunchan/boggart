package io.github.hyunchan.boggart.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** 할 일 목록 → 상세 두 화면. 화면 전환은 상태 하나로 충분해 내비게이션 라이브러리를 쓰지 않는다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var openId by rememberSaveable { mutableStateOf<Int?>(null) }
                val id = openId
                if (id == null) {
                    TodoListScreen(onOpen = { openId = it })
                } else {
                    BackHandler { openId = null }
                    TodoDetailScreen(id, onBack = { openId = null })
                }
            }
        }
    }
}
