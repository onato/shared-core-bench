package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkloadsTest {
    @Test
    fun decodeCountsTheProductsItAcceptsAndRejects() {
        val mascara =
            """{"id":1,"title":"Mascara","price":9.99,"description":"A product","category":"beauty",""" +
                """"brand":"Essence","discountPercentage":7.5,"rating":4.5,"stock":5,"thumbnail":"t.png","tags":[]}"""
        val body = """{"products":[$mascara,{"id":"x"}],"total":2}"""

        assertEquals(IngestResult.Decoded(accepted = 1, rejected = 1), Workloads.decode(body.encodeToByteArray()))
    }

    @Test
    fun decodeReportsABodyThatIsNotJson() {
        assertEquals(IngestResult.InvalidJson, Workloads.decode("not json".encodeToByteArray()))
    }
}
