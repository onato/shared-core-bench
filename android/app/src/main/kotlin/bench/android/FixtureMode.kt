package bench.android

import bench.fixtures.ProductFixtures
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** A client that answers every request with generated product pages instead of calling the network. */
fun fixtureClient(
    records: Int,
    seed: Long,
): OkHttpClient =
    OkHttpClient
        .Builder()
        .addInterceptor(FixtureInterceptor(records, seed))
        .build()

private class FixtureInterceptor(
    private val records: Int,
    private val seed: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val skip = url.queryParameter("skip")!!.toInt()
        val count = minOf(url.queryParameter("limit")!!.toInt(), records - skip)
        val page = ProductFixtures.page(count, seed, skip, total = records)
        return Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .body(page.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
