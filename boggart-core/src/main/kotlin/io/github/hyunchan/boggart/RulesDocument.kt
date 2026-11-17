package io.github.hyunchan.boggart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 규칙 파일의 루트. 에셋 `rules.json`과 저장 규칙 파일이 이 형식이다. */
@Serializable
public data class RulesDocument(
    val version: Int = CURRENT_VERSION,
    val rules: List<Rule> = emptyList(),
) {
    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        public const val CURRENT_VERSION: Int = 1

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /**
         * [text]를 해석한다. `version`이 [CURRENT_VERSION]이 아니면 규칙을 해석하기 전에 [IllegalArgumentException]을 던진다.
         * 모르는 필드는 무시한다.
         */
        public fun parse(text: String): RulesDocument {
            val root = json.parseToJsonElement(text).jsonObject
            val version = root["version"]?.jsonPrimitive?.int ?: CURRENT_VERSION
            require(version == CURRENT_VERSION) { "Unsupported rules document version: $version" }
            return json.decodeFromJsonElement(serializer(), root)
        }
    }
}
