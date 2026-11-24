package bench.android

import bench.android.core.FetchRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpFetcherTest {
    private val server = MockWebServer()
    private val fetcher = OkHttpFetcher(OkHttpClient())

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun okResponseReturnsStatusAndBody() {
        server.enqueue(MockResponse(code = 200, body = """{"products":[]}"""))

        val response = fetcher.fetch(requestFor("/products")) as FetchResult.Response

        assertEquals(200, response.status)
        assertEquals("""{"products":[]}""", response.body.decodeToString())
    }

    @Test
    fun serverErrorReturnsItsStatusAndBody() {
        server.enqueue(MockResponse(code = 500, body = "upstream down"))

        val response = fetcher.fetch(requestFor("/products")) as FetchResult.Response

        assertEquals(500, response.status)
        assertEquals("upstream down", response.body.decodeToString())
    }

    @Test
    fun refusedConnectionReturnsANetworkError() {
        val request = requestFor("/products")
        server.close()

        val result = fetcher.fetch(request)

        assertEquals(FetchResult.NetworkError::class, result::class)
    }

    @Test
    fun stalledResponseTimesOutAsANetworkError() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val impatient = OkHttpFetcher(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build())

        val result = impatient.fetch(requestFor("/products"))

        assertEquals(FetchResult.NetworkError::class, result::class)
    }

    private fun requestFor(path: String) = FetchRequest(requestId = 1, url = server.url(path).toString())
}
