package bench.core

class Core private constructor(
    val config: CoreConfig,
) {
    fun ingest(body: ByteArray): IngestResult = decodePage(body)

    companion object {
        fun create(config: CoreConfig): Core = Core(config)
    }
}
