package soke.musicdelay.speaker;

import java.util.Optional;

/** Stable save IDs; acoustic values will be chosen during listening tests. */
public enum SpeakerType {
    SMALL("small"), LARGE("large"), RADIO("radio"), NETHER("nether"), ENDER("ender");

    private final String id;

    SpeakerType(String id) { this.id = id; }

    public String id() { return id; }

    public static Optional<SpeakerType> byId(String id) {
        for (SpeakerType type : values()) {
            if (type.id.equals(id)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
