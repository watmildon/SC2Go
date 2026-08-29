import StreetComplete
import SwiftUI

@main
struct iOSApp: App {
    init() {
        // starts Koin itself
        StreetCompleteApplicationKt.doInitApp()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
