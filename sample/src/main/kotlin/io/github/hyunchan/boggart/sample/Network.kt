package io.github.hyunchan.boggart.sample

import androidx.annotation.StringRes
import io.github.hyunchan.boggart.ktor.withBoggart
import io.github.hyunchan.boggart.okhttp.BoggartInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val BASE_URL = "https://jsonplaceholder.typicode.com/"

@Serializable
data class Todo(val id: Int, val userId: Int, val title: String, val completed: Boolean)

@Serializable
data class CompletePatch(val completed: Boolean = true)

interface TodoApi {
    @GET("todos")
    suspend fun todos(@Query("_limit") limit: Int = 20): List<Todo>

    @PATCH("todos/{id}")
    suspend fun complete(@Path("id") id: Int, @Body body: CompletePatch = CompletePatch()): Todo
}

/** 실제 앱처럼 응답을 모델로 파싱한다. Boggart가 응답을 바꾸면 그 결과가 화면 상태로 드러난다. */
object Network {
    private val json = Json { ignoreUnknownKeys = true }

    // Retrofit은 OkHttp 인터셉터로 붙인다.
    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(BoggartInterceptor())
        .build()

    private val api: TodoApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TodoApi::class.java)

    // Ktor는 자기 OkHttp 엔진을 쓰므로 인터셉터가 아니라 엔진 래퍼로 붙인다.
    private val ktor = HttpClient(OkHttp.withBoggart())

    /** 할 일 목록(Retrofit). */
    suspend fun todos(): List<Todo> = api.todos()

    /** 할 일 하나(Ktor). 상태 코드는 Retrofit과 같은 방식으로 다루려고 직접 검사한다. */
    suspend fun todo(id: Int): Todo {
        val response = ktor.get("${BASE_URL}todos/$id")
        if (response.status.value !in 200..299) throw HttpStatusException(response.status.value)
        return json.decodeFromString(Todo.serializer(), response.bodyAsText())
    }

    /** 완료로 표시(Retrofit PATCH). */
    suspend fun complete(id: Int): Todo = api.complete(id)
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** 화면이 그리는 불러오기 상태. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Loaded<T>(val value: T) : LoadState<T>

    /** 4xx·5xx 응답. */
    data class ServerError(val code: Int) : LoadState<Nothing>

    /** 200이지만 모델과 형식이 다른 응답. */
    data class ParseError(val detail: String) : LoadState<Nothing>

    /** 응답을 받지 못함(타임아웃, 호스트를 찾지 못함, 끊김). [reason]은 원인별 문구. */
    data class ConnectionError(@param:StringRes val reason: Int, val detail: String) : LoadState<Nothing>
}

/** 요청을 실행해 결과를 [LoadState]로 바꾼다. 앱이 예외 종류별로 다른 화면을 보이는 흔한 방식을 그대로 따른다. */
suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
    LoadState.Loaded(block())
} catch (e: HttpException) {
    LoadState.ServerError(e.code())
} catch (e: HttpStatusException) {
    LoadState.ServerError(e.code)
} catch (e: SerializationException) {
    LoadState.ParseError(e.message.orEmpty())
} catch (e: IllegalArgumentException) {
    // kotlinx 직렬화의 일부 형식 오류는 IllegalArgumentException으로 온다.
    LoadState.ParseError(e.message.orEmpty())
} catch (e: SocketTimeoutException) {
    LoadState.ConnectionError(R.string.connection_timeout, e.toString())
} catch (e: UnknownHostException) {
    LoadState.ConnectionError(R.string.connection_unknown_host, e.toString())
} catch (e: IOException) {
    LoadState.ConnectionError(R.string.connection_failed, e.toString())
}
