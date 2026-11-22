package bench.android

import bench.core.FetchRequest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface FetchResult {
    class Response(
        val status: Int,
        val body: ByteArray,
    ) : FetchResult

    class NetworkError(
        val reason: String,
    ) : FetchResult
}

class OkHttpFetcher(
    private val client: OkHttpClient,
) {
    fun fetch(request: FetchRequest): FetchResult {
        val call = client.newCall(request.toOkHttp())
        return try {
            call.execute().use { FetchResult.Response(it.code, it.body.bytes()) }
        } catch (e: IOException) {
            FetchResult.NetworkError(e.toString())
        }
    }
}

private fun FetchRequest.toOkHttp(): Request =
    Request
        .Builder()
        .url(url)
        .headers(headers.toHeaders())
        .build()
