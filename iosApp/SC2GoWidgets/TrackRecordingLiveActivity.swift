import ActivityKit
import SwiftUI
import WidgetKit

// How a track recording looks in the Dynamic Island and on the Lock Screen.
//
// SF Symbols only, and no colours from the app's asset catalogue: the extension shares exactly one
// file with the app (TrackRecordingAttributes.swift) and nothing else, so that it never has to
// link the Kotlin framework. Red is the colour the map draws a recording track in.
//
// The elapsed time is a `Text(timerInterval:)` rather than a value in the state: SwiftUI counts it
// up by itself, so the clock keeps running between the activity's updates - which are at most one
// every five seconds - instead of costing one per second.
struct TrackRecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackRecordingAttributes.self) { context in
            LockScreenBanner(state: context.state)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(formatTrackDistance(context.state.distanceMeters), systemImage: "figure.walk")
                        .font(.title3)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false)
                        .font(.title3)
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 80)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label(nearbyQuestsText(context.state), systemImage: "mappin.and.ellipse")
                        if let nearest = nearestQuestText(context.state) {
                            Text(nearest)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: "record.circle")
                    .foregroundStyle(.red)
            } compactTrailing: {
                Label("\(context.state.nearbyQuestCount)", systemImage: "mappin.and.ellipse")
                    .font(.caption)
            } minimal: {
                Text("\(context.state.nearbyQuestCount)")
                    .font(.caption)
            }
            // tapping it opens the app, which is the default and all v1 does; deep linking to the
            // nearest quest via sc2go:// is worth doing, but it is a separate piece of work
        }
    }
}

/// The same content in one row, for the Lock Screen and for devices without an Island.
private struct LockScreenBanner: View {
    let state: TrackRecordingAttributes.ContentState

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "record.circle")
                .font(.title2)
                .foregroundStyle(.red)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(formatTrackDistance(state.distanceMeters))
                    Text("·")
                    Text(timerInterval: state.startedAt...Date.distantFuture, countsDown: false)
                        .monospacedDigit()
                        .frame(maxWidth: 70, alignment: .leading)
                }
                .font(.headline)
                Text(nearbyQuestsText(state))
                    .font(.caption)
                if let nearest = nearestQuestText(state) {
                    Text(nearest)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding()
    }
}

private func nearbyQuestsText(_ state: TrackRecordingAttributes.ContentState) -> String {
    // deliberately not localised: the extension shares no resources with the app, and pulling the
    // app's string catalogue in here for two lines is not worth a second copy of every language
    let quests = state.nearbyQuestCount == 1 ? "quest" : "quests"
    return "\(state.nearbyQuestCount) \(quests) within 50 m"
}

private func nearestQuestText(_ state: TrackRecordingAttributes.ContentState) -> String? {
    guard let title = state.nearestQuestTitle, let distance = state.nearestQuestDistanceMeters else {
        return nil
    }
    return "Nearest: \(title) · \(formatTrackDistance(distance))"
}
