package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.BodyFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** `Override.Replace.bodyFile`을 에셋 `<dir>/bodies/<bodyFile>`로 해석한다. 파일이 없으면 null. */
public class PresetBodyFileResolver(
    private val openAsset: (path: String) -> InputStream?,
    public val dir: String = BoggartAndroid.DEFAULT_ASSETS_DIR,
) : BodyFileResolver {
    override suspend fun resolve(path: String): ByteArray? = withContext(Dispatchers.IO) {
        openAsset("$dir/bodies/$path")?.use { it.readBytes() }
    }
}
