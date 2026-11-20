package io.github.hyunchan.boggart.android.control

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import io.github.hyunchan.boggart.control.BoggartControl
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [BoggartControl]을 abstract 네임스페이스 로컬 소켓의 HTTP로 노출한다. 기기 밖에서는 `adb forward`로만 닿는다.
 *
 * ```
 * adb forward tcp:8765 localabstract:boggart-<applicationId>
 * curl localhost:8765/status
 * ```
 *
 * 다른 앱도 같은 이름의 소켓에 연결할 수 있으므로 상대 uid가 shell·root·이 앱일 때만 받는다.
 */
internal class ControlServer(
    val socketName: String,
    private val control: BoggartControl,
    private val logger: (String, Throwable?) -> Unit,
) {
    @Volatile
    private var server: LocalServerSocket? = null

    @Volatile
    private var stopped = false
    private var workers: ExecutorService? = null

    /** 소켓을 열고 받기 시작한다. 이름이 이미 쓰이면(같은 앱의 다른 프로세스 등) 기록만 하고 false. */
    fun start(): Boolean {
        val socket = try {
            LocalServerSocket(socketName)
        } catch (e: IOException) {
            logger("Remote control couldn't open socket '$socketName'", e)
            return false
        }
        server = socket
        val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "Boggart-control").apply { isDaemon = true } }
        workers = pool
        Thread({ acceptLoop(socket, pool) }, "Boggart-control-accept").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        stopped = true
        val socket = server ?: return
        server = null
        // LocalServerSocket.close()는 막혀 있는 accept()를 깨우지 않으므로 직접 연결해 깨운다.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(socketName)) } }
        runCatching { socket.close() }
        workers?.shutdownNow()
    }

    private fun acceptLoop(socket: LocalServerSocket, pool: ExecutorService) {
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!stopped) logger("Remote control stopped accepting", e)
                return
            }
            if (stopped) {
                client.close()
                return
            }
            try {
                pool.execute { handle(client) }
            } catch (_: RuntimeException) {
                client.close()
            }
        }
    }

    private fun handle(client: LocalSocket) {
        client.use {
            try {
                val uid = client.peerCredentials.uid
                if (uid != ROOT_UID && uid != SHELL_UID && uid != Process.myUid()) {
                    ControlHttp.write(client.outputStream, ControlHttp.error(403, "FORBIDDEN", "Only adb shell or this app can use Boggart remote control"))
                    return
                }
                client.soTimeout = READ_TIMEOUT_MS
                ControlHttp.serve(client.inputStream, client.outputStream, control)
            } catch (e: IOException) {
                logger("Remote control request failed", e)
            }
        }
    }

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val READ_TIMEOUT_MS = 30_000
    }
}
