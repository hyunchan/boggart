package io.github.hyunchan.boggart

import kotlinx.serialization.Serializable

/** 매칭 조건과 조작의 쌍. */
@Serializable
public data class Rule(
    val id: String,
    val name: String = id,
    val matcher: RequestMatcher = RequestMatcher(),
    val override: Override,
    val enabled: Boolean = true,
)
