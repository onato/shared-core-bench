package bench.core

data class CoreConfig(
    val dbPath: String,
    val baseUrl: String = "https://dummyjson.com/products",
    val pageSize: Int = 20,
)
