package client.media;

import com.google.gson.JsonObject;

/**
 * Immutable copy of one authoritative source snapshot, shared across signaling and media owners.
 */
record MediaSourceState(JsonObject sources, JsonObject paused) {
    MediaSourceState {
        sources = sources == null ? new JsonObject() : sources.deepCopy();
        paused = paused == null ? new JsonObject() : paused.deepCopy();
    }

    static JsonObject kind(JsonObject all, String kind) {
        return all != null && all.has(kind) && all.get(kind).isJsonObject()
                ? all.getAsJsonObject(kind)
                : new JsonObject();
    }

    boolean retained(String participant, String publication) {
        return paused.has(participant) && publication.equals(paused.get(participant).getAsString());
    }
}
