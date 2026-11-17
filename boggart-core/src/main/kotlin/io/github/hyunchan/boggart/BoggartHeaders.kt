package io.github.hyunchan.boggart

/**
 * 이름 대소문자를 구분하지 않는 불변 HTTP 헤더 멀티맵. 입력 순서와 원래 대소문자를 보존한다.
 */
public class BoggartHeaders private constructor(private val entries: List<Pair<String, String>>) {

    public val size: Int get() = entries.size

    public fun isEmpty(): Boolean = entries.isEmpty()

    /** 이름이 [name]인 첫 번째 값. */
    public operator fun get(name: String): String? =
        entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    public fun values(name: String): List<String> =
        entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    public operator fun contains(name: String): Boolean =
        entries.any { it.first.equals(name, ignoreCase = true) }

    /** 처음 등장한 대소문자 표기의 헤더 이름 집합. */
    public val names: Set<String>
        get() {
            val seen = HashSet<String>()
            return entries.mapNotNullTo(LinkedHashSet()) { (n, _) -> n.takeIf { seen.add(n.lowercase()) } }
        }

    public fun toList(): List<Pair<String, String>> = entries

    /** [name]의 기존 값을 모두 지우고 [value] 하나로 설정한 사본. */
    public fun with(name: String, value: String): BoggartHeaders =
        BoggartHeaders(entries.filterNot { it.first.equals(name, ignoreCase = true) } + (name to value))

    /** [name]을 추가한 사본. 기존 값은 유지한다. */
    public fun plus(name: String, value: String): BoggartHeaders = BoggartHeaders(entries + (name to value))

    public fun without(name: String): BoggartHeaders =
        BoggartHeaders(entries.filterNot { it.first.equals(name, ignoreCase = true) })

    override fun equals(other: Any?): Boolean =
        other is BoggartHeaders && normalized() == other.normalized()

    override fun hashCode(): Int = normalized().hashCode()

    override fun toString(): String = entries.joinToString(prefix = "BoggartHeaders(", postfix = ")") { "${it.first}: ${it.second}" }

    private fun normalized(): List<Pair<String, String>> = entries.map { it.first.lowercase() to it.second }

    public companion object {
        public val EMPTY: BoggartHeaders = BoggartHeaders(emptyList())

        public fun of(vararg pairs: Pair<String, String>): BoggartHeaders = BoggartHeaders(pairs.toList())

        public fun of(pairs: List<Pair<String, String>>): BoggartHeaders = BoggartHeaders(pairs.toList())

        public fun fromMap(map: Map<String, List<String>>): BoggartHeaders =
            BoggartHeaders(map.flatMap { (name, values) -> values.map { name to it } })
    }
}
