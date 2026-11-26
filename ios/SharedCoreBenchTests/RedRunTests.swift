import Testing

struct RedRunTests {
    @Test func failsSoCiIosGoesRed() {
        #expect(1 == 2)
    }
}
