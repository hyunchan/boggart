package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleSource
import io.github.hyunchan.boggart.RulesDocument
import io.github.hyunchan.boggart.TemporaryRuleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * UI 없이 Boggart를 다루는 명령 처리기. 호출 확인, 규칙 추가·수정·삭제·켜고 끄기, 프리셋 토글, 모드 변경을 JSON으로 주고받는다.
 *
 * 전송 방식과 무관하다. 안드로이드 모듈이 이것을 `adb forward`로 닿는 로컬 소켓 HTTP 서버로 노출한다.
 * 명령은 [commands] 표 하나에서 정의되고, 도움말(`GET /`)도 이 표로 만든다.
 *
 * @param savedRules 저장 규칙 출처. 저장을 쓰지 않으면 null. 설치가 바뀌어도 최신 출처를 보도록 매번 부른다.
 * @param presetRules 프리셋 출처. 없으면 null.
 * @param info 상태 응답의 `app`에 실을 값(패키지 이름 등).
 */
public class BoggartControl(
    private val engine: BoggartEngine = Boggart.engine,
    private val temporaryRules: TemporaryRuleSource = Boggart.temporaryRules,
    private val savedRules: () -> ControlRuleStore? = { null },
    private val presetRules: () -> ControlRuleStore? = { null },
    private val info: () -> Map<String, String> = { emptyMap() },
) {
    private val log get() = engine.exchangeLog

    private val temporaryStore = object : ControlRuleStore {
        override val source: RuleSource get() = temporaryRules
        override val editable: Boolean = true

        override suspend fun upsert(rule: Rule) = temporaryRules.add(rule)

        override suspend fun remove(ruleId: String): Boolean = temporaryRules.remove(ruleId)

        override suspend fun clear() = temporaryRules.clear()

        override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
            val rule = temporaryRules.rules.value.firstOrNull { it.id == ruleId } ?: return false
            return temporaryRules.update(rule.copy(enabled = enabled))
        }
    }

    public val commands: List<ControlCommand> = listOf(
        ControlCommand("help", "GET", "/", "List every command with its parameters.") { help() },
        ControlCommand(
            "status",
            "GET",
            "/status",
            "Mode, rule counts per source, and the id of the latest captured call (use it as 'since').",
            tool = ControlTool("boggart_status"),
        ) { status() },
        ControlCommand("mode.get", "GET", "/mode", "Current mode.") { modeJson() },
        ControlCommand(
            "mode.set",
            "PUT",
            "/mode",
            "Change the mode. active: apply rules. paused: capture only, rules ignored. " +
                "offline: calls without a rule fail with UnknownHost. off: bypass Boggart entirely (nothing captured).",
            body = """{"mode": "active" | "paused" | "offline" | "off"}""",
            tool = ControlTool(
                "boggart_set_mode",
                bodyProperties = mapOf("mode" to RuleSchema.enumOf(Mode.entries.map(::modeName), "The new mode.")),
                requiredBody = listOf("mode"),
            ),
        ) { setMode(it) },
        ControlCommand(
            "reset",
            "POST",
            "/reset",
            "Start a clean test: remove every temporary rule, clear captured calls, restart every sequence rule " +
                "and set the mode to active. Saved rules and preset toggles stay.",
            tool = ControlTool("boggart_reset"),
        ) { reset() },
        ControlCommand(
            "calls.list",
            "GET",
            "/calls",
            "Captured calls without bodies, in the order the app received them. A call is captured once the app gets its response " +
                "(after any delay): 'startedAtMs' is when the app sent it and 'timestampMs' when it got the response. " +
                "'lastId' is the newest id in the log.",
            params = CALL_FILTERS + listOf(
                ControlParam("since", "query", "Only calls with an id greater than this.", type = "integer"),
                ControlParam("limit", "query", "Return at most this many of the newest matches. Default $DEFAULT_LIMIT.", type = "integer"),
            ),
            tool = ControlTool("boggart_list_calls"),
        ) { listCalls(it) },
        ControlCommand(
            "calls.wait",
            "GET",
            "/calls/wait",
            "Block until a call matching the filters is captured (when the app has received its response, after any delay), " +
                "then return it with bodies. " +
                "Read 'lastId' from /status before triggering the app, and pass it as 'since' so a fast call isn't missed.",
            params = CALL_FILTERS + listOf(
                ControlParam(
                    "since",
                    "query",
                    "Only calls with an id greater than this. Default: the newest id when the wait starts.",
                    type = "integer",
                ),
                ControlParam(
                    "timeoutMs",
                    "query",
                    "Give up after this long with 408 TIMEOUT. Default $DEFAULT_WAIT_MS, max $MAX_WAIT_MS.",
                    type = "integer",
                ),
            ),
            tool = ControlTool("boggart_wait_for_call"),
        ) { waitCall(it) },
        ControlCommand(
            "calls.get",
            "GET",
            "/calls/{id}",
            "One captured call with request, server response (originalResponse) and the response the app received (deliveredResponse). " +
                "Each body is {size, mediaType, json | text | base64}.",
            params = listOf(ControlParam("id", "path", "Call id.", required = true, type = "integer")),
            tool = ControlTool("boggart_get_call"),
        ) { getCall(it) },
        ControlCommand("calls.clear", "DELETE", "/calls", "Clear captured calls.") {
            log.clear()
            buildJsonObject { put("cleared", true) }
        },
        ControlCommand(
            "rules.list",
            "GET",
            "/rules",
            "Rules in the order the engine tries them. Only the first enabled rule that matches a call applies.",
            params = listOf(SOURCE_PARAM),
            tool = ControlTool("boggart_list_rules"),
        ) { listRules(it) },
        ControlCommand(
            "rules.get",
            "GET",
            "/rules/{id}",
            "One rule.",
            params = listOf(ID_PARAM, SOURCE_PARAM),
        ) { getRule(it) },
        ControlCommand(
            "rules.put",
            "PUT",
            "/rules/{id}",
            "Create or replace a rule by id. Applies from the next call. Without 'target', a rule that is already saved stays saved; " +
                "anything else becomes a temporary rule. Putting a sequence rule again, even unchanged, restarts it from the first step.",
            params = listOf(
                ID_PARAM,
                ControlParam(
                    "target",
                    "query",
                    "temporary (in memory, cleared by reset) or saved (kept across restarts).",
                    enum = listOf(TEMPORARY, SAVED),
                ),
            ),
            body = RULE_BODY,
            tool = ControlTool(
                "boggart_put_rule",
                bodyProperties = mapOf("rule" to RuleSchema.rule(withId = false)),
                requiredBody = listOf("rule"),
                defs = RuleSchema.defs,
            ) { it.getValue("rule") },
        ) { putRule(it) },
        ControlCommand(
            "rules.update",
            "PATCH",
            "/rules/{id}",
            "Turn a rule on or off. Works for preset rules too.",
            params = listOf(ID_PARAM, SOURCE_PARAM),
            body = """{"enabled": true | false}""",
            tool = ControlTool(
                "boggart_set_rule_enabled",
                bodyProperties = mapOf("enabled" to RuleSchema.boolean("true turns the rule on, false turns it off.")),
                requiredBody = listOf("enabled"),
            ),
        ) { updateRule(it) },
        ControlCommand(
            "rules.delete",
            "DELETE",
            "/rules/{id}",
            "Delete a temporary or saved rule. Preset rules can't be deleted; turn them off instead.",
            params = listOf(ID_PARAM, SOURCE_PARAM),
            tool = ControlTool("boggart_delete_rule"),
        ) { deleteRule(it) },
        ControlCommand(
            "rules.import",
            "POST",
            "/rules/import",
            "Apply many rules at once, e.g. a test scenario. Rules with an existing id in the target are replaced.",
            params = listOf(
                ControlParam("target", "query", "temporary (default) or saved.", enum = listOf(TEMPORARY, SAVED)),
                ControlParam("replace", "query", "true removes every rule in the target first.", type = "boolean"),
            ),
            body = """{"version": 1, "rules": [<rule>, ...]} — the same format as assets/boggart/rules.json""",
            tool = ControlTool(
                "boggart_import_rules",
                bodyProperties = mapOf("rules" to RuleSchema.rules()),
                requiredBody = listOf("rules"),
                defs = RuleSchema.defs,
            ) { args ->
                buildJsonObject {
                    put("version", RulesDocument.CURRENT_VERSION)
                    put("rules", args.getValue("rules"))
                }
            },
        ) { importRules(it) },
        ControlCommand(
            "rules.clear",
            "DELETE",
            "/rules",
            "Remove every rule in one editable source.",
            params = listOf(ControlParam("source", "query", "temporary or saved.", required = true, enum = listOf(TEMPORARY, SAVED))),
        ) { clearRules(it) },
    )

    /** [request]를 처리한다. 실패도 예외가 아니라 오류 응답으로 돌려준다. */
    public suspend fun handle(request: ControlRequest): ControlResponse = try {
        val (segments, query) = ControlTarget.parse(request.target)
        val method = request.method.uppercase()
        val byPath = commands.mapNotNull { command -> match(command.segments, segments)?.let { command to it } }
        val (command, pathParams) = byPath.firstOrNull { it.first.method == method }
            ?: if (byPath.isEmpty()) {
                throw ControlException(404, "NOT_FOUND", "No command at '/${segments.joinToString("/")}'. GET / lists every command.")
            } else {
                val allowed = byPath.map { it.first.method }.distinct()
                throw ControlException(405, "METHOD_NOT_ALLOWED", "Use ${allowed.joinToString(" or ")} for this path.")
            }
        val known = command.params.filter { it.location == "query" }.mapTo(HashSet()) { it.name }
        val unknown = query.keys - known
        if (unknown.isNotEmpty()) {
            val hint = if (known.isEmpty()) "It takes no query parameters." else "Known: ${known.sorted().joinToString()}."
            throw ControlException(400, "BAD_REQUEST", "Unknown query parameter ${unknown.joinToString { "'$it'" }} for ${command.name}. $hint")
        }
        command.params.firstOrNull { it.required && it.location == "query" && it.name !in query }?.let {
            throw ControlException(400, "BAD_REQUEST", "Missing query parameter '${it.name}'.")
        }
        ControlResponse(200, command.handler(ControlCall(pathParams, query, request.body)))
    } catch (e: ControlException) {
        error(e.status, e.code, e.message.orEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error(500, "INTERNAL", e.toString())
    }

    private fun match(pattern: List<String>, segments: List<String>): Map<String, String>? {
        if (pattern.size != segments.size) return null
        val params = HashMap<String, String>()
        for ((p, s) in pattern.zip(segments)) {
            if (p.startsWith('{') && p.endsWith('}')) {
                params[p.substring(1, p.length - 1)] = s
            } else if (p != s) {
                return null
            }
        }
        return params
    }

    // ---- 도움말·상태·모드 ----

    private fun help() = buildJsonObject {
        put("service", "boggart")
        put("version", Boggart.VERSION)
        put("errors", "Failures return {\"error\": {\"code\", \"message\"}} with a non-2xx status.")
        put("mcp", "POST /mcp speaks MCP (Streamable HTTP, tools only) with the commands that have a 'tool' name.")
        putJsonArray("commands") {
            for (command in commands) {
                add(
                    buildJsonObject {
                        put("name", command.name)
                        put("method", command.method)
                        put("path", command.path)
                        put("summary", command.summary)
                        if (command.params.isNotEmpty()) {
                            putJsonArray("params") {
                                command.params.forEach { param ->
                                    add(
                                        buildJsonObject {
                                            put("name", param.name)
                                            put("in", param.location)
                                            put("required", param.required)
                                            put("description", param.description)
                                        },
                                    )
                                }
                            }
                        }
                        command.body?.let { put("body", it) }
                        command.tool?.let { put("tool", it.name) }
                    },
                )
            }
        }
    }

    private fun status() = buildJsonObject {
        put("version", Boggart.VERSION)
        put("mode", modeName(engine.mode))
        put("rulesReady", engine.repository.isReady)
        val all = sources()
        putJsonObject("rules") {
            for (origin in listOf(TEMPORARY, SAVED, PRESET)) {
                val inOrigin = all.filter { it.first == origin }
                put(origin, if (inOrigin.isEmpty() && origin != TEMPORARY) null else inOrigin.sumOf { it.second.rules.value.size })
            }
            put(OTHER, all.filter { it.first == OTHER }.sumOf { it.second.rules.value.size })
            put("enabled", engine.repository.snapshot().count { it.enabled })
        }
        val exchanges = log.exchanges.value
        putJsonObject("calls") {
            put("count", exchanges.size)
            put("capacity", log.capacity)
            put("lastId", lastId(exchanges))
        }
        val appInfo = info()
        if (appInfo.isNotEmpty()) putJsonObject("app") { appInfo.forEach { (key, value) -> put(key, value) } }
    }

    private fun modeJson() = buildJsonObject { put("mode", modeName(engine.mode)) }

    private fun setMode(call: ControlCall): JsonObject {
        val body = call.jsonBody()
        requireOnlyKeys(body, "mode")
        val name = (body["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw badRequest("Body needs a string 'mode'.")
        engine.mode = Mode.entries.firstOrNull { modeName(it) == name.lowercase() }
            ?: throw badRequest("Unknown mode '$name'. Use one of ${Mode.entries.joinToString { modeName(it) }}.")
        return modeJson()
    }

    private fun reset(): JsonObject {
        temporaryRules.clear()
        log.clear()
        engine.resetSequences()
        engine.mode = Mode.Active
        return status()
    }

    // ---- 호출 ----

    private fun listCalls(call: ControlCall): JsonObject {
        val filter = callFilter(call)
        val since = call.longParam("since") ?: 0
        val limit = call.longParam("limit")?.toInt() ?: DEFAULT_LIMIT
        if (limit <= 0) throw badRequest("'limit' must be > 0.")
        val exchanges = log.exchanges.value
        val matched = exchanges.filter { it.id > since && filter(it) }.takeLast(limit)
        return buildJsonObject {
            put("lastId", lastId(exchanges))
            put("calls", JsonArray(matched.map(ControlJson::exchangeSummary)))
        }
    }

    private suspend fun waitCall(call: ControlCall): JsonObject {
        val filter = callFilter(call)
        val since = call.longParam("since") ?: lastId(log.exchanges.value)
        val timeoutMs = call.longParam("timeoutMs") ?: DEFAULT_WAIT_MS
        if (timeoutMs !in 0..MAX_WAIT_MS) throw badRequest("'timeoutMs' must be between 0 and $MAX_WAIT_MS.")
        val pick = { list: List<Exchange> -> list.firstOrNull { it.id > since && filter(it) } }
        // 이미 도착한 호출은 기다리지 않고 돌려준다(timeoutMs = 0이어도).
        val found = pick(log.exchanges.value)
            ?: withTimeoutOrNull(timeoutMs) { log.exchanges.mapNotNull(pick).first() }
            ?: throw ControlException(408, "TIMEOUT", "No matching call after id $since within $timeoutMs ms.")
        return buildJsonObject { put("call", ControlJson.exchangeDetail(found)) }
    }

    private fun getCall(call: ControlCall): JsonObject {
        val id = call.pathParams.getValue("id").toLongOrNull() ?: throw badRequest("Call id must be a number.")
        val exchange = log.exchanges.value.firstOrNull { it.id == id }
            ?: throw ControlException(404, "CALL_NOT_FOUND", "No call $id. It may have been cleared or pushed out of the log (capacity ${log.capacity}).")
        return buildJsonObject { put("call", ControlJson.exchangeDetail(exchange)) }
    }

    private fun callFilter(call: ControlCall): (Exchange) -> Boolean {
        val matcher = RequestMatcher(method = call.query["method"], host = call.query["host"], path = call.query["path"])
        return { matcher.matches(it.request) }
    }

    // ---- 규칙 ----

    /** 엔진이 보는 순서의 (출처 이름, 출처). */
    private fun sources(): List<Pair<String, RuleSource>> {
        val saved = savedRules()?.source
        val preset = presetRules()?.source
        return engine.repository.sources.value.map { source ->
            val origin = when {
                source === temporaryRules -> TEMPORARY
                source === saved -> SAVED
                source === preset -> PRESET
                else -> OTHER
            }
            origin to source
        }
    }

    private fun store(origin: String): ControlRuleStore? = when (origin) {
        TEMPORARY -> temporaryStore
        SAVED -> savedRules()
        PRESET -> presetRules()
        else -> null
    }

    /** 순차 응답 규칙이면 진행 위치를 함께 싣는다. */
    private fun ruleJson(rule: Rule, origin: String): JsonObject =
        ControlJson.rule(rule, origin, engine.nextStep(rule).takeIf { rule.override is Override.Sequence })

    private fun listRules(call: ControlCall): JsonObject {
        val only = call.query["source"]?.let(::checkSource)
        val rules = sources().filter { only == null || it.first == only }
            .flatMap { (origin, source) -> source.rules.value.map { ruleJson(it, origin) } }
        return buildJsonObject { put("rules", JsonArray(rules)) }
    }

    private fun getRule(call: ControlCall): JsonObject {
        val (origin, rule) = findRule(call)
        return buildJsonObject { put("rule", ruleJson(rule, origin)) }
    }

    private suspend fun putRule(call: ControlCall): JsonObject {
        val id = call.pathParams.getValue("id")
        val body = call.jsonBody()
        (body["id"] as? JsonPrimitive)?.content?.let { if (it != id) throw badRequest("Body id '$it' doesn't match the path id '$id'.") }
        // GET으로 받은 규칙을 그대로 고쳐 보낼 수 있게 출력 전용 필드는 버린다.
        val fields = JsonObject(body - "source" - "sequence" + ("id" to JsonPrimitive(id)))
        val rule = decodeRule(fields)
        val origin = call.query["target"]?.let(::checkTarget)
            ?: if (savedRules()?.source?.rules?.value?.any { it.id == id } == true) SAVED else TEMPORARY
        val store = editableStore(origin)
        val created = store.source.rules.value.none { it.id == id }
        store.upsert(rule)
        // 같은 내용을 다시 넣으면 출처의 StateFlow가 기존 인스턴스를 유지하므로 커서를 직접 되돌린다.
        engine.resetSequence(id)
        return buildJsonObject {
            put("created", created)
            put("rule", ruleJson(rule, origin))
        }
    }

    private suspend fun updateRule(call: ControlCall): JsonObject {
        val body = call.jsonBody()
        requireOnlyKeys(body, "enabled")
        val enabled = (body["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw badRequest("Body needs a boolean 'enabled'.")
        val (origin, rule) = findRule(call)
        val store = store(origin) ?: throw readOnly("Rule '${rule.id}' comes from a source the app added; it can't be changed here.")
        store.setEnabled(rule.id, enabled)
        val updated = store.source.rules.value.firstOrNull { it.id == rule.id } ?: rule.copy(enabled = enabled)
        return buildJsonObject { put("rule", ruleJson(updated, origin)) }
    }

    private suspend fun deleteRule(call: ControlCall): JsonObject {
        val (origin, rule) = findRule(call)
        val store = store(origin)?.takeIf { it.editable }
            ?: throw readOnly("Rule '${rule.id}' is a $origin rule and can't be deleted. Turn it off with PATCH {\"enabled\": false}.")
        store.remove(rule.id)
        return buildJsonObject { put("deleted", ruleJson(rule, origin)) }
    }

    private suspend fun importRules(call: ControlCall): JsonObject {
        val text = call.body?.takeIf { it.isNotBlank() } ?: throw badRequest("Body needs a rules document.")
        val document = try {
            RulesDocument.parse(text)
        } catch (e: SerializationException) {
            throw ControlException(400, "INVALID_RULE", e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw ControlException(400, "INVALID_RULE", e.message.orEmpty())
        }
        val duplicates = document.rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw ControlException(400, "INVALID_RULE", "Duplicate rule id ${duplicates.joinToString { "'$it'" }}.")
        val origin = call.query["target"]?.let(::checkTarget) ?: TEMPORARY
        val replace = when (call.query["replace"]) {
            null, "false" -> false
            "true" -> true
            else -> throw badRequest("'replace' must be true or false.")
        }
        val store = editableStore(origin)
        if (replace) store.clear()
        document.rules.forEach {
            store.upsert(it)
            engine.resetSequence(it.id)
        }
        return buildJsonObject {
            put("imported", document.rules.size)
            put("target", origin)
            put("ids", buildJsonArray { document.rules.forEach { add(JsonPrimitive(it.id)) } })
        }
    }

    private suspend fun clearRules(call: ControlCall): JsonObject {
        val origin = checkTarget(call.query.getValue("source"))
        val store = editableStore(origin)
        val count = store.source.rules.value.size
        store.clear()
        return buildJsonObject {
            put("source", origin)
            put("removed", count)
        }
    }

    private fun findRule(call: ControlCall): Pair<String, Rule> {
        val id = call.pathParams.getValue("id")
        val only = call.query["source"]?.let(::checkSource)
        return sources().filter { only == null || it.first == only }
            .firstNotNullOfOrNull { (origin, source) -> source.rules.value.firstOrNull { it.id == id }?.let { origin to it } }
            ?: throw ControlException(404, "RULE_NOT_FOUND", "No rule '$id'${only?.let { " in $it" }.orEmpty()}. GET /rules lists them.")
    }

    private fun editableStore(origin: String): ControlRuleStore = when (origin) {
        TEMPORARY -> temporaryStore
        else -> savedRules() ?: throw ControlException(409, "SAVED_RULES_DISABLED", "Saved rules are turned off in this app (persistence = false).")
    }

    private fun decodeRule(fields: JsonObject): Rule = try {
        ControlJson.strict.decodeFromJsonElement(Rule.serializer(), fields)
    } catch (e: SerializationException) {
        throw ControlException(400, "INVALID_RULE", e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        throw ControlException(400, "INVALID_RULE", e.message.orEmpty())
    }

    // ---- 공통 ----

    private fun ControlCall.jsonBody(): JsonObject {
        val text = body?.takeIf { it.isNotBlank() } ?: throw badRequest("This command needs a JSON body.")
        val element = try {
            ControlJson.strict.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ControlException(400, "INVALID_JSON", e.message.orEmpty())
        }
        return element as? JsonObject ?: throw ControlException(400, "INVALID_JSON", "Body must be a JSON object.")
    }

    private fun ControlCall.longParam(name: String): Long? =
        query[name]?.let { it.toLongOrNull() ?: throw badRequest("'$name' must be a number.") }

    private fun requireOnlyKeys(body: JsonObject, vararg keys: String) {
        val unknown = body.keys - keys.toSet()
        if (unknown.isNotEmpty()) throw badRequest("Unknown field ${unknown.joinToString { "'$it'" }}. Only ${keys.joinToString { "'$it'" }} is accepted.")
    }

    private fun checkSource(value: String): String =
        value.takeIf { it in ORIGINS } ?: throw badRequest("Unknown source '$value'. Use one of ${ORIGINS.joinToString()}.")

    private fun checkTarget(value: String): String =
        value.takeIf { it == TEMPORARY || it == SAVED } ?: throw badRequest("Unknown target '$value'. Use temporary or saved.")

    private fun lastId(exchanges: List<Exchange>): Long = exchanges.lastOrNull()?.id ?: 0

    private fun error(status: Int, code: String, message: String) = ControlResponse(
        status,
        buildJsonObject {
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    private fun badRequest(message: String) = ControlException(400, "BAD_REQUEST", message)

    private fun readOnly(message: String) = ControlException(409, "READ_ONLY", message)

    private companion object {
        const val TEMPORARY = "temporary"
        const val SAVED = "saved"
        const val PRESET = "preset"
        const val OTHER = "other"
        val ORIGINS = listOf(TEMPORARY, SAVED, PRESET, OTHER)

        const val DEFAULT_LIMIT = 50
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 120_000L

        val ID_PARAM = ControlParam("id", "path", "Rule id.", required = true)
        val SOURCE_PARAM = ControlParam("source", "query", "Limit to one source: temporary, saved, preset, or other.", enum = ORIGINS)
        val CALL_FILTERS = listOf(
            ControlParam("method", "query", "HTTP method, e.g. GET."),
            ControlParam("host", "query", "Exact host."),
            ControlParam("path", "query", "Path glob as in rule matchers: * is one segment, ** is any number of segments."),
        )
        const val RULE_BODY = """A rule as in rules.json: {"name"?, "matcher"?: {"method", "host", "path", "query", "headers"}, """ +
            """"override": {"type": "replace" | "patch" | "jsonPatch" | "fail", ...}, "enabled"?}. The id comes from the path."""

        fun modeName(mode: Mode): String = mode.name.lowercase()
    }
}
