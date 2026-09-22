import StreetComplete
import SwiftUI

@main
struct iOSApp: App {
    init() {
        // starts Koin itself
        StreetCompleteApplicationKt.doInitApp()
        // after doInitApp, not before: it resolves the track recorder out of Koin
        TrackRecordingLiveActivityController.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
