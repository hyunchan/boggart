package io.github.hyunchan.boggart.android

import java.io.File
import java.io.IOException

/** 같은 디렉토리의 임시 파일에 쓴 뒤 rename으로 교체한다. 쓰기 도중 프로세스가 죽어도 기존 파일이 남는다. */
internal object AtomicFile {
    fun readText(file: File): String? = if (file.exists()) file.readText() else null

    fun writeText(file: File, text: String) {
        val dir = checkNotNull(file.absoluteFile.parentFile) { "No parent directory: $file" }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val temp = File(dir, "${file.name}.tmp")
        temp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Cannot replace $file")
        }
    }
}
