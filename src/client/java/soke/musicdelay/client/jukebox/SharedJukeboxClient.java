package soke.musicdelay.client.jukebox;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.BrowsableTrack;
import soke.musicdelay.client.VanillaTrackRegistry;
import soke.musicdelay.client.gui.RecordUploadToast;
import soke.musicdelay.jukebox.SharedMusicFiles;
import soke.musicdelay.network.RecordCustomDataPayload;
import soke.musicdelay.network.SharedMusicPacket;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class SharedJukeboxClient {
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "always-play-client-files"); t.setDaemon(true); return t; });
    private static final Map<String, Playback> sessions = new LinkedHashMap<>();
    private static final Set<String> ready = new HashSet<>();
    private static final Set<String> checking = new HashSet<>();
    private static final Set<String> failed = new HashSet<>();
    private static final LinkedHashSet<String> wanted = new LinkedHashSet<>();
    private static final LinkedHashSet<String> prefetchPending = new LinkedHashSet<>();
    private static final LinkedHashSet<String> prefetchWanted = new LinkedHashSet<>();
    private static final Set<String> prefetchFailed = new HashSet<>();
    private static final Set<String> cancelling = new HashSet<>();
    private static final int PREFETCH_QUEUE_LIMIT = 64;
    private static final long PREFETCH_BUDGET = 128L * 1024 * 1024;
    private static long prefetchedBytes;
    private static boolean receivingBackground;
    private static long backgroundRetryAt;
    private static Object world;
    private static long generation;
    private static Upload upload;
    private static boolean preparing;
    private static String receiving;
    private static byte[] incoming;
    private static int received;
    private static long requestTime;
    private static long retryAt;

    private static final class Upload {
        final String hash; final byte[] bytes; final String token = UUID.randomUUID().toString();
        int sent; int acknowledged; boolean accepted; long touched = System.nanoTime();
        Upload(String hash, byte[] bytes) { this.hash = hash; this.bytes = bytes; }
    }
    private static final class Playback {
        final String id; final String value; final BlockPos pos;
        volatile long elapsed; volatile long updated; boolean started;
        Playback(SharedMusicPacket packet) {
            id = packet.id(); value = packet.value(); pos = packet.pos().immutable(); update(packet);
        }
        void update(SharedMusicPacket packet) { elapsed = Math.max(0, packet.number()); updated = System.nanoTime(); }
        long offset() { return elapsed + (System.nanoTime() - updated) / 1_000_000; }
    }
    private static Path cache() { return Minecraft.getInstance().gameDirectory.toPath().resolve("always-play-audio-cache"); }
    public static boolean supported() { return ClientPlayNetworking.canSend(SharedMusicPacket.TYPE); }
    public static void message(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.translatable("music-delay-reducer.network." + key));
    }
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SharedMusicPacket.TYPE, (packet, context) ->
                context.client().execute(() -> { ensureWorld(context.client()); receive(packet); }));
    }
    public static void record(BrowsableTrack track, String title, String composer) {
        if (!supported()) { message("server_required"); return; }
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (preparing || upload != null) { message("busy"); return; }
        var expected = player.getMainHandItem().copy();
        int slot = player.getInventory().getSelectedSlot();
        if (!soke.musicdelay.jukebox.SharedJukeboxServer.clean(expected) || expected.getCount() != 1) {
            message("hold_disc"); return;
        }
        String safeTitle = title.substring(0, Math.min(64, title.length()));
        String safeComposer = composer.substring(0, Math.min(64, composer.length()));
        if (track.kind == BrowsableTrack.Kind.CUSTOM) {
            if (preparing || upload != null) { message("busy"); return; }
            preparing = true; long epoch = generation;
            RecordUploadToast.begin();
            IO.execute(() -> {
                String errorKey = "file_read_failed";
                try {
                    byte[] bytes;
                    try (var stream = Files.newInputStream(track.customPath)) { bytes = stream.readNBytes(SharedMusicPacket.MAX_FILE + 1); }
                    if (bytes.length == 0) throw new IllegalArgumentException("Empty track file");
                    errorKey = "file_too_large";
                    if (bytes.length > SharedMusicPacket.MAX_FILE) throw new IllegalArgumentException("Track must be at most 32 MiB");
                    errorKey = "prepare_failed";
                    long duration = duration(AudioTrack.open(new ByteArrayInputStream(bytes)));
                    errorKey = "cache_write_failed";
                    String hash = SharedMusicFiles.hash(bytes);
                    // Cache the exact bytes that will be uploaded, not a mutable source path.
                    SharedMusicFiles.store(cache(), hash, bytes, 512L * 1024 * 1024);
                    Minecraft.getInstance().execute(() -> {
                        if (epoch != generation) return;
                        preparing = false;
                        if (!supported()) { RecordUploadToast.failed(); return; }
                        if (!stillHolding(expected, slot)) { RecordUploadToast.failed(); message("hold_disc"); return; }
                        ready.add(hash); upload = new Upload(hash, bytes);
                        RecordUploadToast.progress(0);
                        ClientPlayNetworking.send(new SharedMusicPacket("upload_begin", hash, upload.token, safeTitle, safeComposer,
                                BlockPos.ZERO, duration, bytes.length, new byte[0]));
                    });
                } catch (Exception e) {
                    MusicDelayReducer.LOGGER.warn("Cannot prepare shared track: " + track.customPath, e);
                    String failureKey = errorKey;
                    Minecraft.getInstance().execute(() -> { if (epoch == generation) { preparing = false; RecordUploadToast.failed(); message(failureKey); } });
                }
            });
            return;
        }
        String kind; String value;
        if (track.kind == BrowsableTrack.Kind.DISC) {
            Identifier song = VanillaTrackRegistry.getJukeboxSongIdForSoundLocation(track.vanillaSound.getLocation());
            if (song == null) { message("missing_track"); return; }
            kind = "VANILLA_DISC"; value = song.toString();
        } else if (track.kind == BrowsableTrack.Kind.AMBIENT) {
            if (preparing) { message("busy"); return; }
            preparing = true; long epoch = generation;
            Identifier location = track.vanillaSound.getLocation();
            Identifier resource = Identifier.fromNamespaceAndPath(location.getNamespace(), "sounds/" + location.getPath() + ".ogg");
            var found = Minecraft.getInstance().getResourceManager().getResource(resource);
            if (found.isEmpty()) { preparing = false; message("missing_track"); return; }
            message("preparing");
            IO.execute(() -> {
                try {
                    long length = duration(AudioTrack.open(found.get().open()));
                    Minecraft.getInstance().execute(() -> {
                        if (epoch != generation) return;
                        preparing = false;
                        if (!stillHolding(expected, slot)) { message("hold_disc"); return; }
                        if (supported()) ClientPlayNetworking.send(new SharedMusicPacket("record_builtin", "", "track:" + location,
                                safeTitle, safeComposer, BlockPos.ZERO, length, slot, new byte[0]));
                    });
                } catch (Exception e) {
                    MusicDelayReducer.LOGGER.warn("Cannot prepare vanilla record " + location, e);
                    Minecraft.getInstance().execute(() -> { if (epoch == generation) { preparing = false; message("audio_failed"); } });
                }
            });
            return;
        } else return;
        ClientPlayNetworking.send(new RecordCustomDataPayload(kind, value, safeTitle, safeComposer));
    }

    private static long duration(AudioTrack decoder) throws Exception {
        try {
            long frames = 0; byte[] buffer = new byte[16384]; int count;
            while ((count = decoder.read(buffer)) >= 0) {
                frames += count / (decoder.getChannels() * 2);
                if (frames / decoder.getSampleRate() > 1800) throw new IllegalArgumentException("Track exceeds 30 minutes");
            }
            long length = (long) Math.ceil(frames * 1000.0 / decoder.getSampleRate());
            if (length <= 0) throw new IllegalArgumentException("Empty audio track");
            return length;
        } finally { decoder.close(); }
    }

    private static boolean stillHolding(net.minecraft.world.item.ItemStack expected, int slot) {
        var p = Minecraft.getInstance().player;
        return p != null && p.getInventory().getSelectedSlot() == slot
                && net.minecraft.world.item.ItemStack.matches(p.getMainHandItem(), expected);
    }

    private static void receive(SharedMusicPacket packet) {
        switch (packet.action()) {
            case "upload_ready" -> {
                if (matchesUpload(packet)) { upload.accepted = true; upload.touched = System.nanoTime(); }
            }
            case "upload_rejected" -> {
                if (matchesUpload(packet)) { upload = null; RecordUploadToast.failed(); }
            }
            case "upload_progress" -> {
                if (matchesUpload(packet) && packet.total() == upload.bytes.length
                        && packet.number() >= upload.acknowledged && packet.number() <= upload.sent) {
                    upload.acknowledged = (int) packet.number();
                    upload.touched = System.nanoTime();
                    int percent = (int) (100L * upload.acknowledged / upload.bytes.length);
                    if (percent == 100) RecordUploadToast.saving();
                    else RecordUploadToast.progress(percent);
                }
            }
            case "upload_complete" -> {
                if (matchesUpload(packet)) { upload = null; RecordUploadToast.succeeded(); }
            }
            case "prefetch" -> {
                String hash = packet.id();
                if (SharedMusicFiles.validHash(hash) && !ready.contains(hash) && !checking.contains(hash)
                        && !wanted.contains(hash) && !prefetchWanted.contains(hash) && !prefetchFailed.contains(hash)
                        && !hash.equals(receiving) && prefetchPending.size() + prefetchWanted.size() < PREFETCH_QUEUE_LIMIT)
                    prefetchPending.add(hash);
            }
            case "play" -> {
                Playback old = sessions.get(packet.id());
                if (old == null) {
                    if (sessions.size() >= 128) return;
                    sessions.values().removeIf(s -> {
                        if (!s.pos.equals(packet.pos())) return false;
                        PositionalCustomTrackPlayer.stop(s.pos); return true;
                    });
                    old = new Playback(packet); sessions.put(old.id, old);
                    if (old.value.startsWith("SHARED|")) {
                        String hash = old.value.substring(7);
                        failed.remove(hash); prefetchFailed.remove(hash);
                        prioritize(hash);
                    }
                } else old.update(packet);
                startIfReady(old);
            }
            case "stop" -> {
                Playback old = sessions.remove(packet.id());
                if (old != null) PositionalCustomTrackPlayer.stop(old.pos);
            }
            case "retry" -> {
                if (packet.id().equals(receiving)) {
                    if (receivingBackground) backgroundRetryAt = System.nanoTime() + 2_000_000_000L;
                    else retryAt = System.nanoTime() + 2_000_000_000L;
                    clearDownload();
                }
            }
            case "failed" -> {
                if (packet.id().equals(receiving)) { downloadFailed(receiving); clearDownload(); }
            }
            case "cancelled" -> cancelling.remove(packet.id());
            case "download_chunk" -> download(packet);
            default -> { }
        }
    }
    private static boolean matchesUpload(SharedMusicPacket packet) {
        return upload != null && upload.hash.equals(packet.id()) && upload.token.equals(packet.value());
    }
    private static void startIfReady(Playback playback) {
        if (playback.started) return;
        if (!PositionalCustomTrackPlayer.hasCapacity()) return;
        if (playback.value.startsWith("SHARED|")) {
            String hash = playback.value.substring(7);
            if (!SharedMusicFiles.validHash(hash) || failed.contains(hash)) return;
            if (!ready.contains(hash)) {
                if (!checking.contains(hash) && !wanted.contains(hash)) checkCache(hash);
                return;
            }
            playback.started = true;
            Path file = SharedMusicFiles.file(cache(), hash);
            PositionalCustomTrackPlayer.startAt(playback.pos, () -> AudioTrack.open(file), playback::offset);
        } else if (playback.value.startsWith("VANILLA|track:")) {
            playback.started = true;
            try {
                Identifier id = Identifier.parse(playback.value.substring("VANILLA|track:".length()));
                Identifier resource = Identifier.fromNamespaceAndPath(id.getNamespace(), "sounds/" + id.getPath() + ".ogg");
                var found = Minecraft.getInstance().getResourceManager().getResource(resource);
                if (found.isEmpty()) { message("missing_track"); return; }
                PositionalCustomTrackPlayer.startAt(playback.pos, () -> AudioTrack.open(found.get().open()), playback::offset);
            } catch (RuntimeException e) { message("missing_track"); }
        }
    }
    private static boolean needed(String hash) {
        return sessions.values().stream().anyMatch(s -> !s.started && s.value.equals("SHARED|" + hash));
    }

    private static void prioritize(String hash) {
        prefetchPending.remove(hash);
        if (prefetchWanted.remove(hash)) wanted.add(hash);
        if (ready.contains(hash)) return;
        retryAt = 0;
        if (receiving == null || !receivingBackground) return;
        if (receiving.equals(hash)) {
            receivingBackground = false;
            wanted.add(hash);
            ClientPlayNetworking.send(SharedMusicPacket.simple("prioritize", hash, ""));
        } else {
            // A background transfer must not delay a record that has actually been inserted.
            cancelling.add(receiving);
            ClientPlayNetworking.send(SharedMusicPacket.simple("cancel_download", receiving, ""));
            clearDownload();
        }
    }

    private static void downloadFailed(String hash) {
        wanted.remove(hash); prefetchWanted.remove(hash); prefetchPending.remove(hash);
        if (needed(hash)) { failed.add(hash); message("transfer_failed"); }
        else if (prefetchFailed.size() < 256) prefetchFailed.add(hash);
    }

    private static void checkCache(String hash) { checkCache(hash, false); }
    private static void checkCache(String hash, boolean background) {
        if (checking.size() >= 4) return;
        checking.add(hash); long epoch = generation;
        IO.execute(() -> {
            boolean valid = false;
            try {
                Path file = SharedMusicFiles.file(cache(), hash);
                if (Files.isRegularFile(file) && Files.size(file) <= SharedMusicPacket.MAX_FILE) {
                    valid = SharedMusicFiles.hash(Files.readAllBytes(file)).equals(hash);
                }
            } catch (Exception e) { MusicDelayReducer.LOGGER.debug("Audio cache miss: {}", hash); }
            boolean found = valid;
            Minecraft.getInstance().execute(() -> {
                if (epoch != generation) return;
                checking.remove(hash);
                if (found) { ready.add(hash); sessions.values().forEach(SharedJukeboxClient::startIfReady); }
                else if (!background || needed(hash)) wanted.add(hash);
                else prefetchWanted.add(hash);
            });
        });
    }
    private static void download(SharedMusicPacket packet) {
        if (!packet.id().equals(receiving)) return;
        if (packet.total() <= 0 || packet.total() > SharedMusicPacket.MAX_FILE || packet.number() != received
                || packet.data().length == 0 || received + packet.data().length > packet.total()
                || (incoming != null && incoming.length != packet.total())) {
            downloadFailed(receiving); clearDownload(); return;
        }
        if (incoming == null) incoming = new byte[packet.total()];
        System.arraycopy(packet.data(), 0, incoming, received, packet.data().length);
        received += packet.data().length; requestTime = System.nanoTime();
        if (receivingBackground) prefetchedBytes += packet.data().length;
        if (received == incoming.length) {
            String hash = receiving; byte[] bytes = incoming; long epoch = generation;
            boolean background = receivingBackground;
            wanted.remove(hash); prefetchWanted.remove(hash); checking.add(hash); clearDownload();
            IO.execute(() -> {
                try {
                    SharedMusicFiles.store(cache(), hash, bytes, (background ? 448L : 512L) * 1024 * 1024);
                    Minecraft.getInstance().execute(() -> {
                        if (epoch != generation) return;
                        checking.remove(hash); ready.add(hash); sessions.values().forEach(SharedJukeboxClient::startIfReady);
                    });
                } catch (Exception e) {
                    MusicDelayReducer.LOGGER.warn("Cannot cache shared track " + hash, e);
                    Minecraft.getInstance().execute(() -> {
                        if (epoch == generation) { checking.remove(hash); downloadFailed(hash); }
                    });
                }
            });
        }
    }
    private static void clearDownload() { receiving = null; incoming = null; received = 0; receivingBackground = false; }
    public static void tick(Minecraft client) {
        ensureWorld(client);
        if (client.level == null || !supported()) return;
        if (upload != null) {
            if (System.nanoTime() - upload.touched > 120_000_000_000L) { upload = null; RecordUploadToast.failed(); message("transfer_failed"); }
            else if (upload.accepted) {
                for (int n = 0; n < 2 && upload.sent < upload.bytes.length; n++) {
                    int end = Math.min(upload.bytes.length, upload.sent + SharedMusicPacket.CHUNK);
                    ClientPlayNetworking.send(new SharedMusicPacket("upload_chunk", upload.hash, upload.token, "", "", BlockPos.ZERO,
                            upload.sent, upload.bytes.length, Arrays.copyOfRange(upload.bytes, upload.sent, end)));
                    upload.sent = end;
                }
                // Keep the operation pending until the server confirms the actual record write.
            }
        }
        if (receiving != null && System.nanoTime() - requestTime > 30_000_000_000L) {
            ClientPlayNetworking.send(SharedMusicPacket.simple("cancel_download", receiving, ""));
            downloadFailed(receiving); clearDownload();
        }
        // Retry deferred cache checks / source allocation as capacity becomes available.
        sessions.values().forEach(SharedJukeboxClient::startIfReady);
        while (checking.size() < 4 && !prefetchPending.isEmpty()) {
            String hash = prefetchPending.iterator().next(); prefetchPending.remove(hash);
            if (!ready.contains(hash) && !checking.contains(hash) && !wanted.contains(hash)
                    && !prefetchWanted.contains(hash) && !prefetchFailed.contains(hash)) checkCache(hash, true);
        }
        if (receiving == null) {
            if (!wanted.isEmpty() && System.nanoTime() >= retryAt) {
                receiving = wanted.stream().filter(h -> !cancelling.contains(h)).findFirst().orElse(null); receivingBackground = false;
            } else if (wanted.isEmpty() && sessions.values().stream().noneMatch(s -> !s.started && s.value.startsWith("SHARED|"))
                    && System.nanoTime() >= backgroundRetryAt && !prefetchWanted.isEmpty()
                    && prefetchedBytes <= PREFETCH_BUDGET - SharedMusicPacket.MAX_FILE) {
                receiving = prefetchWanted.stream().filter(h -> !cancelling.contains(h)).findFirst().orElse(null); receivingBackground = true;
            }
            if (receiving != null) {
                requestTime = System.nanoTime();
                ClientPlayNetworking.send(SharedMusicPacket.simple("download", receiving, receivingBackground ? "prefetch" : ""));
            }
        }
        sessions.values().removeIf(s -> {
            if (client.isPaused() || System.nanoTime() - s.updated < 10_000_000_000L) return false;
            PositionalCustomTrackPlayer.stop(s.pos); return true;
        });
    }
    private static void ensureWorld(Minecraft client) {
        if (world != client.level) {
            String previous = receiving;
            boolean cancel = previous != null && client.level != null && supported();
            reset(); world = client.level;
            if (cancel) {
                cancelling.add(previous);
                ClientPlayNetworking.send(SharedMusicPacket.simple("cancel_download", previous, ""));
            }
        }
    }
    public static void reset() {
        RecordUploadToast.clear();
        generation++; world = null; sessions.clear(); wanted.clear(); failed.clear(); checking.clear(); ready.clear();
        prefetchPending.clear(); prefetchWanted.clear(); prefetchFailed.clear(); cancelling.clear();
        prefetchedBytes = 0; backgroundRetryAt = 0;
        upload = null; preparing = false; retryAt = 0; clearDownload(); PositionalCustomTrackPlayer.stopAll();
    }
    private SharedJukeboxClient() { }
}