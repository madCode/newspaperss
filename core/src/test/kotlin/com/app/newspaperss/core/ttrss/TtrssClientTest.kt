package com.app.newspaperss.core.ttrss

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TtrssClientTest {
    private val api = "https://rss.example.com/tt-rss/api/"

    /** Answers each POST with the next queued (status, body), recording what was sent. */
    private class FakeServer(private val url: String) : HttpClient {
        val sent = mutableListOf<JsonObject>()
        val replies = ArrayDeque<Pair<Int, String>>()
        var finalUrl = url

        fun reply(body: String, code: Int = 200) = replies.addLast(code to body)

        override suspend fun postJson(url: String, body: String): HttpResponse {
            assertEquals(this.url, url)
            sent += Json.parseToJsonElement(body).jsonObject
            val (code, reply) = replies.removeFirst()
            return HttpResponse(code, finalUrl, "application/json", reply)
        }
        override suspend fun get(url: String): HttpResponse = throw IOException("not used")
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
    }

    private val server = FakeServer(api)
    private fun client(password: String = "secret") = TtrssClient(server, api, "reader", password)

    private fun ok(content: String) = """{"seq":0,"status":0,"content":$content}"""
    private fun error(code: String) = """{"seq":0,"status":1,"content":{"error":"$code"}}"""
    private val loggedIn = ok("""{"session_id":"sid-1","api_level":18}""")

    // Shaped like a real getHeadlines reply: tt-rss 21+ sends feed_id as an int.
    private val headlines = ok(
        """[
          {"id":12345,"guid":"SHA1:abc","unread":true,"marked":false,"published":false,"updated":1759125600,
           "is_updated":false,"title":"The Title","link":"https://news.example/story","feed_id":111,"tags":[""],
           "content":"<p>Full text</p>","labels":[],"feed_title":"Example News","comments_count":0,"comments_link":"",
           "always_display_attachments":false,"author":"Ada","score":0,"note":null,"lang":"en","flavor_image":"","flavor_stream":""},
          {"id":"123","title":"Older","link":"https://blog.example/post","feed_id":"7","content":"","author":"",
           "feed_title":"A Blog","updated":1759000000}
        ]""",
    )

    private fun JsonObject.str(key: String) = get(key)?.toString()

    @Test
    fun loginSendsTheCredentialsAndReturnsTheSession() = runTest {
        server.reply(loggedIn)
        assertEquals("sid-1", client().login())
        assertEquals(Json.parseToJsonElement("""{"op":"login","user":"reader","password":"secret"}"""), server.sent.single())
    }

    @Test
    fun aPasswordWithQuotesAndBackslashesIsEscaped() = runTest {
        val password = "pa\"ss\\word\n,\"op\":\"x"
        server.reply(loggedIn)
        client(password).login()
        val sent = server.sent.single()
        assertEquals(JsonPrimitive(password), sent["password"])
        assertEquals(setOf("op", "user", "password"), sent.keys)
    }

    @Test
    fun headlinesLogInFirstAndAskForUnreadWithContent() = runTest {
        server.reply(loggedIn)
        server.reply(headlines)
        val items = client().unreadHeadlines(limit = 50, sinceId = 99)

        val request = server.sent[1]
        assertEquals(
            Json.parseToJsonElement(
                """{"sid":"sid-1","op":"getHeadlines","feed_id":-4,"view_mode":"unread","show_content":true,"limit":50,"since_id":99}""",
            ),
            request,
        )
        assertEquals(2, items.size)
        with(items[0]) {
            assertEquals(12345L, id)
            assertEquals("The Title", title)
            assertEquals("https://news.example/story", link)
            assertEquals("<p>Full text</p>", content)
            assertEquals("Ada", author)
            assertEquals(1759125600L, updated)
            assertEquals("Example News", feedTitle)
            assertEquals("111", feedId)
        }
        // Older servers send ids as strings, and blank fields mean "none".
        with(items[1]) {
            assertEquals(123L, id)
            assertEquals("7", feedId)
            assertNull(content)
            assertNull(author)
        }
    }

    @Test
    fun aCategoryIsRequestedAsOne() = runTest {
        server.reply(loggedIn)
        server.reply(ok("[]"))
        client().unreadHeadlines(feedId = 3, isCategory = true)
        val request = server.sent[1]
        assertEquals("3", request.str("feed_id"))
        assertEquals("true", request.str("is_cat"))
        assertFalse("since_id" in request)
    }

    @Test
    fun categoriesLeaveOutTtrssOwnGroups() = runTest {
        server.reply(loggedIn)
        server.reply(ok("""[{"id":"2","title":"News","unread":4},{"id":-1,"title":"Special"},{"id":-2,"title":"Labels"},{"id":0,"title":"Uncategorized"}]"""))
        assertEquals(listOf(TtrssCategory(2, "News"), TtrssCategory(0, "Uncategorized")), client().categories())
        assertEquals("\"getCategories\"", server.sent[1].str("op"))
    }

    @Test
    fun headlinesWithoutALinkOrIdAreSkipped() = runTest {
        server.reply(loggedIn)
        server.reply(ok("""[{"id":1,"title":"No link","feed_id":1},{"title":"No id","link":"https://a.example/","feed_id":1}]"""))
        assertTrue(client().unreadHeadlines().isEmpty())
    }

    @Test
    fun anExpiredSessionLogsInAgainOnce() = runTest {
        server.reply(loggedIn)
        server.reply(error("NOT_LOGGED_IN"))
        server.reply(ok("""{"session_id":"sid-2"}"""))
        server.reply(ok("[]"))
        client().unreadHeadlines()
        assertEquals(listOf("login", "getHeadlines", "login", "getHeadlines"), server.sent.map { it.str("op")!!.trim('"') })
        assertEquals("\"sid-2\"", server.sent[3].str("sid"))
    }

    @Test
    fun aSessionThatStaysRejectedIsAnErrorNotALoop() = runTest {
        repeat(2) {
            server.reply(loggedIn)
            server.reply(error("NOT_LOGGED_IN"))
        }
        val e = runCatching { client().unreadHeadlines() }.exceptionOrNull()
        assertTrue(e is TtrssException.ApiError)
        assertEquals(4, server.sent.size)
    }

    @Test
    fun loginAndApiErrorsAreTyped() = runTest {
        server.reply(error("LOGIN_ERROR"))
        assertTrue(runCatching { client().login() }.exceptionOrNull() is TtrssException.LoginFailed)

        server.reply(error("API_DISABLED"))
        val disabled = runCatching { client().login() }.exceptionOrNull()
        assertTrue(disabled is TtrssException.ApiDisabled)
        assertTrue(disabled!!.message!!.startsWith("Enable the API in tt-rss preferences"))
    }

    @Test
    fun errorsNeverCarryTheRequest() = runTest {
        val password = "hunter2-very-secret"
        for (reply in listOf(error("LOGIN_ERROR"), error("SOMETHING_ELSE"), "<html>Welcome</html>", ok("[]"))) {
            server.reply(reply)
            val message = runCatching { client(password).login() }.exceptionOrNull()!!.message!!
            assertFalse(message, message.contains(password))
        }
        server.reply("oops", code = 502)
        val http = runCatching { client(password).login() }.exceptionOrNull()
        assertEquals(502, (http as TtrssException.HttpError).code)
    }

    @Test
    fun somethingThatIsntTtrssSaysSo() = runTest {
        server.reply("<!doctype html><title>My blog</title>")
        assertTrue(runCatching { client().login() }.exceptionOrNull() is TtrssException.NotTtrss)
    }

    @Test
    fun aRedirectIsReportedWithTheNewAddress() = runTest {
        server.finalUrl = "https://rss.example.com/api/"
        server.reply("", code = 308)
        val e = runCatching { client().login() }.exceptionOrNull()
        assertEquals("https://rss.example.com/api/", (e as TtrssException.Redirected).to)
    }

    @Test
    fun markReadClearsTheUnreadFlag() = runTest {
        server.reply(loggedIn)
        server.reply(ok("""{"status":"OK","updated":2}"""))
        client().markRead(listOf(12345L, 123L))
        assertEquals(
            Json.parseToJsonElement("""{"sid":"sid-1","op":"updateArticle","article_ids":"12345,123","mode":0,"field":2}"""),
            server.sent[1],
        )
    }

    @Test
    fun markingNothingSendsNothing() = runTest {
        client().markRead(emptyList())
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun logoutEndsTheSessionOnlyIfThereIsOne() = runTest {
        val c = client()
        c.logout()
        assertTrue(server.sent.isEmpty())

        server.reply(loggedIn)
        server.reply(ok("""{"status":"OK"}"""))
        c.login()
        c.logout()
        assertEquals(Json.parseToJsonElement("""{"sid":"sid-1","op":"logout"}"""), server.sent[1])
    }

    @Test
    fun apiUrlIsBuiltFromWhatTheReaderTyped() {
        assertEquals("https://rss.example.com/tt-rss/api/", TtrssClient.apiUrl(" rss.example.com/tt-rss/ "))
        assertEquals("http://10.0.0.2:8080/api/", TtrssClient.apiUrl("http://10.0.0.2:8080"))
        assertEquals("https://rss.example.com/api/", TtrssClient.apiUrl("https://rss.example.com/api"))
        assertEquals("https://rss.example.com/api/", TtrssClient.apiUrl("https://rss.example.com/api/"))
    }

    @Test
    fun unexpectedContentIsNotTtrss() = runTest {
        server.reply(loggedIn)
        server.reply(ok("""{"not":"a list"}"""))
        try {
            client().unreadHeadlines()
            fail()
        } catch (e: TtrssException.NotTtrss) {
        }
    }
}
