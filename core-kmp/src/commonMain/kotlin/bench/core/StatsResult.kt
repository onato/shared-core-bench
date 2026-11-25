package bench.core

sealed interface StatsResult {
    data class Stats(
        val stats: CoreStats,
    ) : StatsResult

    data object StorageFailed : StatsResult
}
