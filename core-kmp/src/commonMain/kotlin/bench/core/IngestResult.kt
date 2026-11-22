package bench.core

sealed interface IngestResult {
    data class Decoded(
        val accepted: Int,
        val rejected: Int,
    ) : IngestResult
}
