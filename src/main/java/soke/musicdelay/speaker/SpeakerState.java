package soke.musicdelay.speaker;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable saved state, independent of block position and client audio objects.
 * Moving the speaker must carry this state, not call create() again.
 * Position is a checkpoint, not a clock: the playback service supplies it.
 */
public record SpeakerState(
        UUID id,
        SpeakerType type,
        Optional<UUID> ownerId,
        boolean main,
        boolean enabled,
        float volume,
        float effectStrength,
        String trackReference,
        long positionMillis
) {
    public SpeakerState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(trackReference, "trackReference");
        checkUnit(volume, "volume");
        checkUnit(effectStrength, "effectStrength");
        if (trackReference.length() > 512) throw new IllegalArgumentException("Track reference too long");
        if (positionMillis < 0) throw new IllegalArgumentException("Negative playback position");
        if (trackReference.isEmpty() && positionMillis != 0) {
            throw new IllegalArgumentException("Position without a track");
        }
    }

    /** Call only for a genuinely new speaker on the logical server. */
    public static SpeakerState create(SpeakerType type) {
        return new SpeakerState(UUID.randomUUID(), type, Optional.empty(),
                false, false, 1.0f, 0.5f, "", 0);
    }

    /** A new physical copy, preserving settings without sharing its source identity. */
    public SpeakerState copyForNewSpeaker() {
        return new SpeakerState(UUID.randomUUID(), type, ownerId, main, enabled,
                volume, effectStrength, trackReference, positionMillis);
    }

    public SpeakerState withMain(boolean value) {
        return new SpeakerState(id, type, ownerId, value, enabled,
                volume, effectStrength, trackReference, positionMillis);
    }

    public SpeakerState withEnabled(boolean value) {
        return new SpeakerState(id, type, ownerId, main, value,
                volume, effectStrength, trackReference, positionMillis);
    }

    public SpeakerState withOwner(Optional<UUID> value) {
        return new SpeakerState(id, type, value, main, enabled,
                volume, effectStrength, trackReference, positionMillis);
    }

    public SpeakerState withLevels(float newVolume, float newEffectStrength) {
        return new SpeakerState(id, type, ownerId, main, enabled,
                newVolume, newEffectStrength, trackReference, positionMillis);
    }

    public SpeakerState withPlayback(String reference, long offsetMillis) {
        return new SpeakerState(id, type, ownerId, main, enabled,
                volume, effectStrength, reference, offsetMillis);
    }

    private static void checkUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and within 0..1");
        }
    }
}
