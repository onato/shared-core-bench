package bench.core

sealed interface CategoriesResult {
    data class Categories(
        val names: List<String>,
    ) : CategoriesResult

    data object StorageFailed : CategoriesResult
}
