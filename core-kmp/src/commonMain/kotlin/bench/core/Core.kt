package bench.core

class Core private constructor(
    val config: CoreConfig,
) {
    companion object {
        fun create(config: CoreConfig): Core = Core(config)
    }
}
