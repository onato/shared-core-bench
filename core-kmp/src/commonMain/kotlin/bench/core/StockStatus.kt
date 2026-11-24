package bench.core

enum class StockStatus(
    val from: Long,
) {
    InStock(from = 10),
    LowStock(from = 1),
    OutOfStock(from = Long.MIN_VALUE),
    ;

    companion object {
        fun of(stock: Long): StockStatus = entries.first { stock >= it.from }
    }
}
