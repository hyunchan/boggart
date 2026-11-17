package io.github.hyunchan.boggart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestMatcherTest {
    private fun req(url: String, method: String = "GET", headers: BoggartHeaders = BoggartHeaders.EMPTY) =
        BoggartRequest(method, url, headers)

    private fun path(pattern: String, path: String) =
        RequestMatcher(path = pattern).matches(req("https://api.example.com$path"))

    @Test
    fun `empty matcher matches everything`() {
        assertTrue(RequestMatcher().matches(req("http://x.io/")))
        assertTrue(RequestMatcher().matches(req("http://x.io/a/b?c=d", method = "POST")))
    }

    @Test
    fun `single star matches exactly one segment`() {
        assertTrue(path("/api/v1/users/*", "/api/v1/users/42"))
        assertFalse(path("/api/v1/users/*", "/api/v1/users"))
        assertFalse(path("/api/v1/users/*", "/api/v1/users/"))
        assertFalse(path("/api/v1/users/*", "/api/v1/users/42/posts"))
    }

    @Test
    fun `star inside segment matches partial segment`() {
        assertTrue(path("/files/*.json", "/files/a.json"))
        assertFalse(path("/files/*.json", "/files/a.xml"))
        assertFalse(path("/files/*.json", "/files/dir/a.json"))
    }

    @Test
    fun `double star matches zero or more segments`() {
        assertTrue(path("/api/**", "/api"))
        assertTrue(path("/api/**", "/api/a"))
        assertTrue(path("/api/**", "/api/a/b/c"))
        assertTrue(path("/api/**/posts", "/api/posts"))
        assertTrue(path("/api/**/posts", "/api/users/1/posts"))
        assertFalse(path("/api/**/posts", "/api/users/1/comments"))
        assertFalse(path("/api/**", "/other"))
    }

    @Test
    fun `root pattern`() {
        assertTrue(path("/", "/"))
        assertTrue(path("/", ""))
        assertFalse(path("/", "/a"))
        assertTrue(path("/**", "/"))
        assertTrue(path("/**", "/a/b"))
        assertFalse(path("/*", "/"))
    }

    @Test
    fun `trailing slash is ignored`() {
        assertTrue(path("/api/users", "/api/users/"))
        assertTrue(path("/api/users/", "/api/users"))
    }

    @Test
    fun `method and host are case insensitive`() {
        val matcher = RequestMatcher(method = "post", host = "API.example.com")
        assertTrue(matcher.matches(req("https://api.example.com/x", method = "POST")))
        assertFalse(matcher.matches(req("https://api.example.com/x", method = "GET")))
        assertFalse(matcher.matches(req("https://other.example.com/x", method = "POST")))
    }

    @Test
    fun `query conditions`() {
        val matcher = RequestMatcher(query = mapOf("page" to "2", "q" to "*"))
        assertTrue(matcher.matches(req("http://x.io/s?q=hello&page=1&page=2")))
        assertFalse(matcher.matches(req("http://x.io/s?q=hello&page=1")))
        assertFalse(matcher.matches(req("http://x.io/s?page=2")))
        assertTrue(RequestMatcher(query = mapOf("q" to "a b")).matches(req("http://x.io/s?q=a%20b")))
    }

    @Test
    fun `header conditions ignore name case`() {
        val matcher = RequestMatcher(headers = mapOf("x-env" to "qa", "Authorization" to "*"))
        val headers = BoggartHeaders.of("X-Env" to "qa", "authorization" to "Bearer t")
        assertTrue(matcher.matches(req("http://x.io/", headers = headers)))
        assertFalse(matcher.matches(req("http://x.io/", headers = BoggartHeaders.of("X-Env" to "qa"))))
        assertFalse(matcher.matches(req("http://x.io/", headers = headers.with("X-ENV", "prod"))))
    }

    @Test
    fun `url parsing`() {
        val url = BoggartUrl.parse("HTTPS://user@Api.Example.com:8443/v1/items?id=1&id=2&flag#frag")
        assertEquals("https", url.scheme)
        assertEquals("api.example.com", url.host)
        assertEquals(8443, url.port)
        assertEquals("/v1/items", url.path)
        assertEquals(listOf("1", "2"), url.queryParameters["id"])
        assertEquals("", url.queryParameter("flag"))
        assertEquals(443, BoggartUrl.parse("https://a.io").port)
        assertEquals("/", BoggartUrl.parse("https://a.io?x=1").path)
        assertEquals("https://a.io/p?x=1", BoggartUrl.parse("https://a.io:443/p?x=1").toString())
    }

    @Test
    fun `headers multimap`() {
        val headers = BoggartHeaders.of("Set-Cookie" to "a", "set-cookie" to "b", "X" to "1")
        assertEquals("a", headers["SET-COOKIE"])
        assertEquals(listOf("a", "b"), headers.values("Set-Cookie"))
        assertEquals(setOf("Set-Cookie", "X"), headers.names)
        assertEquals(BoggartHeaders.of("x" to "1"), headers.without("set-cookie"))
    }

    @Test
    fun `isCoveredBy tells when one matcher always wins over another`() {
        val exact = RequestMatcher("GET", "api.example.com", "/feed/v2/news")
        assertTrue(exact.isCoveredBy(RequestMatcher(path = "/feed/**")))
        assertTrue(exact.isCoveredBy(RequestMatcher("get", "API.example.com", "/feed/*/news")))
        assertTrue(exact.isCoveredBy(exact))
        assertTrue(exact.isCoveredBy(RequestMatcher()))
        assertFalse(exact.isCoveredBy(RequestMatcher("POST", path = "/feed/**")))
        assertFalse(exact.isCoveredBy(RequestMatcher(path = "/feed/*")))
        // 덮는 쪽에만 있는 조건은 덮지 못한다.
        assertFalse(exact.isCoveredBy(exact.copy(headers = mapOf("X-Test" to "*"))))
        assertTrue(exact.copy(headers = mapOf("x-test" to "1")).isCoveredBy(exact.copy(headers = mapOf("X-Test" to "*"))))
        // 글롭끼리는 같거나 상대가 ** 일 때만 덮는다고 본다.
        val glob = RequestMatcher(path = "/feed/*")
        assertTrue(glob.isCoveredBy(RequestMatcher(path = "/**")))
        assertFalse(glob.isCoveredBy(RequestMatcher(path = "/feed/a*")))
        assertFalse(RequestMatcher(path = "/feed/**").isCoveredBy(glob))
        // 경로 조건이 없으면 모든 경로라서 경로 조건이 있는 쪽에 덮이지 않는다.
        assertFalse(RequestMatcher("GET").isCoveredBy(RequestMatcher("GET", path = "/feed/**")))
    }
}
