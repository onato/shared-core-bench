package bench.core

sealed interface IngestResult {
    data class Decoded(
        val accepted: Int,
        val rejected: Int,
    ) : IngestResult

    data object UnknownRequest : IngestResult

    data class HttpStatus(
        val status: Int,
    ) : IngestResult

    data object InvalidJson : IngestResult

    data object NotAProductPage : IngestResult

    data object StorageFailed : IngestResult
}
