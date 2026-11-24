package bench.android.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeSharedCoreTest {
    private val mascara = Product(id = 1, title = "Essence Mascara", price = 9.99)
    private val palette = Product(id = 2, title = "Eyeshadow Palette", price = 19.0)
    private val canister = Product(id = 3, title = "Powder Canister", price = 14.99)

    @Test
    fun pagesOfStoredProductsFollowNextCursorInIdOrder() {
        val core = FakeSharedCore(stored = listOf(canister, mascara, palette))

        val first = core.page(cursor = null, size = 2)
        val second = core.page(cursor = 2, size = 2)

        assertEquals(PageResult.Page(listOf(mascara, palette), nextCursor = 2), first)
        assertEquals(PageResult.Page(listOf(canister), nextCursor = null), second)
    }

    @Test
    fun ingestingTheFetchedRequestStoresTheNextRemotePage() {
        val core = FakeSharedCore(stored = listOf(mascara), remotePages = listOf(listOf(palette), listOf(canister)))

        val request = core.nextFetch()
        val result = core.ingest(requestId = 1, status = 200, body = ByteArray(0))

        assertEquals(FetchRequest(requestId = 1, url = "https://fake.test/products?page=1"), request)
        assertEquals(IngestResult.Ingested(accepted = 1, rejected = 0), result)
        assertEquals(PageResult.Page(listOf(mascara, palette), nextCursor = null), core.page(cursor = null, size = 5))
        assertEquals(FetchRequest(requestId = 2, url = "https://fake.test/products?page=2"), core.nextFetch())
    }

    @Test
    fun thereIsNothingToFetchOnceEveryRemotePageIsIngested() {
        val core = FakeSharedCore(remotePages = listOf(listOf(palette)))

        core.ingest(requestId = 1, status = 200, body = ByteArray(0))

        assertEquals(null, core.nextFetch())
    }

    @Test
    fun aNon2xxStatusLeavesTheSameRequestPending() {
        val core = FakeSharedCore(remotePages = listOf(listOf(palette)))

        val result = core.ingest(requestId = 1, status = 503, body = ByteArray(0))

        assertEquals(IngestResult.HttpStatus(503), result)
        assertEquals(FetchRequest(requestId = 1, url = "https://fake.test/products?page=1"), core.nextFetch())
    }

    @Test
    fun ingestingARequestItDidNotIssueFailsAndStoresNothing() {
        val core = FakeSharedCore(remotePages = listOf(listOf(palette)))

        val result = core.ingest(requestId = 7, status = 200, body = ByteArray(0))

        assertEquals(IngestResult.Failed(IngestFailure.UnknownRequest), result)
        assertEquals(CoreStats(productCount = 0), core.stats())
    }

    @Test
    fun detailFindsAStoredProductByIdAndNothingForAnUnknownId() {
        val paletteDetail =
            ProductDetail(
                id = 2,
                title = "Eyeshadow Palette",
                description = "Twelve shades",
                category = "beauty",
                brand = "Glamour Beauty",
                price = 19.5,
                discountPercentage = 5.5,
                discountedPrice = 18.43,
                rating = 3.5,
                ratingBucket = RatingBucket.Fair,
                stock = 44,
                stockStatus = StockStatus.InStock,
                thumbnail = "https://cdn.dummyjson.com/2.png",
                tags = listOf("beauty", "eyeshadow"),
            )
        val core = FakeSharedCore(details = listOf(paletteDetail))

        assertEquals(paletteDetail, core.detail(id = 2))
        assertEquals(null, core.detail(id = 3))
    }
}
