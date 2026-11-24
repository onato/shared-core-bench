package bench.core

/** Which products a page holds; an empty [text] matches every product. */
data class Query(
    val text: String = "",
)
