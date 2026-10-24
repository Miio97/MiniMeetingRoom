package common.meeting;

/** Requested device state is separate from publishing confirmed by the media engine. */
public record MediaState(
        boolean micRequested,
        boolean cameraRequested,
        boolean shareRequested,
        boolean audioPublishing,
        boolean videoPublishing,
        boolean sharePublishing) {}
