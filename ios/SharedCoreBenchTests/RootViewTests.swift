import SwiftUI
import Testing
import ViewInspector
@testable import SharedCoreBench

@MainActor
struct RootViewTests {
    @Test func showsTheProductsTitle() throws {
        let title = try RootView().inspect().find(text: "Products").string()

        #expect(title == "Products")
    }
}
