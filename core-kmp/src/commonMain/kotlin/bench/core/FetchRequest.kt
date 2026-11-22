package bench.core

data class FetchRequest(
    val requestId: Long,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
