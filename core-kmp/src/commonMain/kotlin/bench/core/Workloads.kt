package bench.core

/** Entry points for the core workloads (SPEC.md 6–9) that no contract call isolates. */
object Workloads {
    /** Benchmark 6: decode and validate a page without storing it. */
    fun decode(body: ByteArray): IngestResult =
        when (val page = decodePage(body)) {
            is PageDecoding.Unreadable -> page.error
            is PageDecoding.Products -> IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
        }
}
