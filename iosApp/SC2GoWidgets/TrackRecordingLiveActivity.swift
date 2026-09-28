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
//
// `context.isStale` is honoured in every presentation. The app gives each update a staleDate a
// couple of minutes out, so a recording whose app was killed - ActivityKit outlives the process -
// stops looking like a recording: the counter is replaced by "not updating" and the red goes grey,
// rather than a Lock Screen banner counting up a walk that nothing is recording any more.
struct TrackRecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackRecordingAttributes.self) { context in
            LockScreenBanner(state: context.state, isStale: context.isStale)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(formatTrackDistance(context.state.distanceMeters), systemImage: "figure.walk")
                        .font(.title3)
                        .foregroundStyle(context.isStale ? Color.secondary : Color.primary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ElapsedTime(startedAt: context.state.startedAt, isStale: context.isStale)
                        .font(.title3)
                        .multilineTextAlignment(.trailing)
                        // wide enough for a survey that ran past an hour, i.e. 1:23:45
                        .frame(maxWidth: 110)
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
                    .foregroundStyle(context.isStale ? Color.secondary : Color.primary)
                }
            } compactLeading: {
                Image(systemName: context.isStale ? "exclamationmark.triangle" : "record.circle")
                    .foregroundStyle(context.isStale ? Color.secondary : Color.red)
            } compactTrailing: {
                // an HStack rather than a Label: the compact slot is a few points wide, and a
                // Label's default icon spacing truncates a two-digit count to "..."
                HStack(spacing: 2) {
                    Image(systemName: "mappin.and.ellipse")
                    Text("\(context.state.nearbyQuestCount)")
                        .monospacedDigit()
                        .minimumScaleFactor(0.8)
                }
                .font(.caption)
                .foregroundStyle(context.isStale ? Color.secondary : Color.primary)
            } minimal: {
                // the count alone, greyed, would be indistinguishable from a live one at this size
                if context.isStale {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(context.state.nearbyQuestCount)")
                        .font(.caption)
                }
            }
            // tapping it opens the app, which is the default and all v1 does; deep linking to the
            // nearest quest via sc2go:// is worth doing, but it is a separate piece of work
        }
    }
}

/// The same content in one row, for the Lock Screen and for devices without an Island.
private struct LockScreenBanner: View {
    let state: TrackRecordingAttributes.ContentState
    let isStale: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: isStale ? "exclamationmark.triangle" : "record.circle")
                .font(.title2)
                .foregroundStyle(isStale ? Color.secondary : Color.red)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(formatTrackDistance(state.distanceMeters))
                    Text("·")
                    ElapsedTime(startedAt: state.startedAt, isStale: isStale)
                        // wide enough for a survey that ran past an hour, i.e. 1:23:45
                        .frame(maxWidth: 100, alignment: .leading)
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
        .foregroundStyle(isStale ? Color.secondary : Color.primary)
    }
}

/// How long the recording has been running - or, once the activity has gone stale, that it is not
/// running any more. A stale activity must not keep counting: the count is SwiftUI's own and would
/// go on for as long as the banner is on the Lock Screen, long after the app that fed it is gone.
private struct ElapsedTime: View {
    let startedAt: Date
    let isStale: Bool

    var body: some View {
        if isStale {
            Text("not updating")
                .lineLimit(2)
                .minimumScaleFactor(0.7)
        } else {
            Text(timerInterval: startedAt...Date.distantFuture, countsDown: false)
                .monospacedDigit()
                .lineLimit(1)
                // rather than truncating once the survey has been running for over an hour
                .minimumScaleFactor(0.7)
        }
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
