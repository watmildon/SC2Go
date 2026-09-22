import SwiftUI
import WidgetKit

// The widget extension. It exists only to draw the track recording Live Activity - there are no
// home screen widgets - and it deliberately does NOT link the Kotlin framework: that framework is
// a 155 MB static one, and a widget extension is launched by the system under a tight memory
// limit. Everything it draws arrives in TrackRecordingAttributes.ContentState, which is the one
// file it shares with the app.
@main
struct SC2GoWidgets: WidgetBundle {
    var body: some Widget {
        TrackRecordingLiveActivity()
    }
}
