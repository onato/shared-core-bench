package bench.fixtures

import kotlin.random.Random

/** Deterministic product pages in the dummyjson shape, so every run and every core ingests the same bytes. */
object ProductFixtures {
    private const val MIN_CENTS = 100
    private const val MAX_CENTS = 100_000
    private const val CENTS = 100.0

    fun page(
        count: Int,
        seed: Long,
    ): ByteArray = page(count, seed, skip = 0, total = count)

    /** The [count] products after the first [skip] of [total]; ids number from `skip + 1`. */
    fun page(
        count: Int,
        seed: Long,
        skip: Int,
        total: Int,
    ): ByteArray {
        val random = Random(seed)
        val products =
            (skip + 1..skip + count).joinToString(",") { id ->
                val price = random.nextInt(MIN_CENTS, MAX_CENTS) / CENTS
                """{"id":$id,"title":"Product ${random.nextLong().toULong()}","description":"A product",""" +
                    """"category":"beauty","brand":"Essence","price":$price,"discountPercentage":7.5,"rating":4.5,""" +
                    """"stock":5,"thumbnail":"https://cdn.dummyjson.com/$id.png","tags":["beauty","bench"]}"""
            }
        return """{"products":[$products],"total":$total,"skip":$skip,"limit":$count}""".encodeToByteArray()
    }
}
