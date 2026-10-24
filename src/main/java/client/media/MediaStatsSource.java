package client.media;

import dev.onvoid.webrtc.RTCStatsReport;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Exists for the duration of one sampling round, never stored in the history buffer. */
record MediaStatsSource(
        Object identity,
        Kind kind,
        boolean sending,
        boolean transportOnly,
        Supplier<CompletableFuture<RTCStatsReport>> read) {
    enum Kind {
        CAMERA,
        AUDIO,
        SCREEN
    }
}
