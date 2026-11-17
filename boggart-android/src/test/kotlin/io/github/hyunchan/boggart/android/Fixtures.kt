package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RulesDocument
import java.io.InputStream

internal class FakeAssets(private val files: Map<String, String>) : (String) -> InputStream? {
    val opened = mutableListOf<String>()

    override fun invoke(path: String): InputStream? {
        opened += path
        return files[path]?.byteInputStream()
    }
}

internal fun rule(id: String, enabled: Boolean = true, path: String = "/users/*") =
    Rule(id, matcher = RequestMatcher(path = path), override = Override.Replace(body = id), enabled = enabled)

internal fun rulesJson(vararg rules: Rule): String = RulesDocument(rules = rules.toList()).encode()
