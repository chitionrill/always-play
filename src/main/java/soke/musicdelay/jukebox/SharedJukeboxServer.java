package soke.musicdelay.jukebox;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.ModConfig;
import soke.musicdelay.network.RecordCustomDataPayload;
import soke.musicdelay.network.SharedMusicPacket;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Server authority for recording, persistent audio and playback sessions. */
public final class SharedJukeboxServer {
    private static SharedJukeboxServer current;
    private final MinecraftServer server;
    private final Path root;
    private final ExecutorService io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { Thread t = new Thread(r, "always-play-server-files"); t.setDaemon(true); return t; });
    private final Set<String> stored = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, RecordingReservation> reservations = new HashMap<>();
    private final Map<UUID, Download> downloads = new HashMap<>();
    private final Map<UUID, LoadRequest> loading = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, Long>> offers = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, ClientPreparation>> preparations = new HashMap<>();
    private final Map<UUID, PendingInsertion> pendingInsertions = new HashMap<>();

    private static final class ClientPreparation {
        final String token = UUID.randomUUID().toString();
        int percent;
        long updated = -10000;
        long requested = -10000;
    }
    private static final class PendingInsertion {
        final String id = UUID.randomUUID().toString();
        final ServerLevel level;
        final BlockPos pos;
        final CustomRecordData record;
        final ItemStack expected;
        final int slot;
        final long created;
        int lastPercent = Integer.MIN_VALUE;
        long lastSent;
        PendingInsertion(ServerPlayer owner, ServerLevel level, BlockPos pos, CustomRecordData record, long ticks) {
            this.level = level; this.pos = pos.immutable(); this.record = record;
            expected = owner.getMainHandItem().copy(); slot = owner.getInventory().getSelectedSlot(); created = ticks;
        }
    }
    private static final int MAX_OFFERS = 128;
    private static final long OFFER_LIFETIME = 2400;

    private static final class LoadRequest {
        final String hash;
        boolean background; volatile boolean cancelled;
        LoadRequest(String hash, boolean background) { this.hash = hash; this.background = background; }
    }
    private final Map<UUID, Long> nextUpload = new HashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private volatile boolean alive = true;
    private long ticks;

    private static final class Upload {
        final String hash; final String token; final String title; final String composer;
        final byte[] bytes; final long duration; int received; int lastPercent = -1; long touched; boolean saving;
        Upload(ServerPlayer p, SharedMusicPacket packet, long tick) {
            hash = packet.id(); token = packet.value(); title = packet.title(); composer = packet.composer();

            bytes = new byte[packet.total()]; touched = tick; duration = packet.number();
        }
    }
    private static final class Download {
        final String hash; final byte[] bytes; int offset; boolean background;
        Download(String hash, byte[] bytes, boolean background) { this.hash = hash; this.bytes = bytes; this.background = background; }
    }
    private static final class Session {
        final String id = UUID.randomUUID().toString(); final ServerLevel level; final BlockPos pos;
        boolean announced; final CustomRecordData record; final long start; final Set<UUID> listeners = new HashSet<>();
        Session(ServerLevel level, BlockPos pos, CustomRecordData record) {
            this.level = level; this.pos = pos.immutable(); this.record = record; start = level.getGameTime();
        }
    }

    private volatile String cacheIdentity;
    private static String cacheLabel(String value) {
        return value == null ? "" : value.substring(0, Math.min(64, value.length()));
    }

    private SharedJukeboxServer(MinecraftServer server) {
        this.server = server;
        root = server.getWorldPath(LevelResource.ROOT).resolve("always-play-audio");
        io.execute(() -> {
            try {
                Files.createDirectories(root);
                Path identityFile = root.resolve("world-id.txt");
                if (Files.isRegularFile(identityFile)) cacheIdentity = UUID.fromString(Files.readString(identityFile).trim()).toString();
                else {
                    String identity = UUID.randomUUID().toString();
                    Files.writeString(identityFile, identity, java.nio.file.StandardOpenOption.CREATE_NEW);
                    cacheIdentity = identity;
                }
                try (var files = Files.list(root)) {
                    files.filter(Files::isRegularFile).forEach(p -> {
                        String n = p.getFileName().toString();
                        if (n.endsWith(".audio") && SharedMusicFiles.validHash(n.substring(0, n.length() - 6)))
                            stored.add(n.substring(0, n.length() - 6));
                    });
                }
            } catch (Exception e) { MusicDelayReducer.LOGGER.error("Cannot open world audio library", e); }
        });
    }

    public static boolean recordable(ItemStack stack) {
        return !stack.isEmpty() && (stack.has(DataComponents.JUKEBOX_PLAYABLE)
                || (CustomRecordData.isPresent(stack) && stack.getItem().getDefaultInstance().has(DataComponents.JUKEBOX_PLAYABLE)));
    }
    public static boolean clean(ItemStack stack) {
        return recordable(stack) && stack.has(DataComponents.CUSTOM_NAME)
                && "Clean".equals(stack.getHoverName().getString());
    }
    private static void tell(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable("music-delay-reducer.network." + key));
    }
    private static void send(ServerPlayer player, SharedMusicPacket packet) {
        if (ServerPlayNetworking.canSend(player, SharedMusicPacket.TYPE)) ServerPlayNetworking.send(player, packet);
    }
    private void rejectUpload(ServerPlayer player, SharedMusicPacket packet, String key) {
        releaseReservation(player, packet.value());
        tell(player, key); send(player, SharedMusicPacket.simple("upload_rejected", packet.id(), packet.value()));
    }

    private void failUpload(ServerPlayer player, Upload upload, String key) {
        releaseReservation(player, upload.token);
        tell(player, key);
        send(player, SharedMusicPacket.simple("upload_rejected", upload.hash, upload.token));
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(RecordCustomDataPayload.TYPE, RecordCustomDataPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SharedMusicPacket.TYPE, SharedMusicPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SharedMusicPacket.TYPE, SharedMusicPacket.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> current = new SharedJukeboxServer(s));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (current != null && current.server == s) {
                for (ServerPlayer player : s.getPlayerList().getPlayers()) current.releaseReservation(player);
                current.alive = false; current.io.shutdownNow(); current.uploads.clear();
                current.downloads.clear(); current.sessions.clear(); current = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (current != null && current.server == s) current.tick(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            if (current != null) {
                UUID id = handler.player.getUUID();
                current.releaseReservation(handler.player);
                current.uploads.remove(id); current.downloads.remove(id); current.loading.remove(id);
                current.nextUpload.remove(id); current.offers.remove(id);
                current.preparations.remove(id); current.pendingInsertions.remove(id);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(SharedMusicPacket.TYPE, (packet, context) ->
                context.server().execute(() -> { if (current != null) current.receive(context.player(), packet); }));
        ServerPlayNetworking.registerGlobalReceiver(RecordCustomDataPayload.TYPE, (packet, context) ->
                context.server().execute(() -> { if (current != null) current.record(context.player(), packet); }));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer p)
                    || !(level instanceof ServerLevel world) || current == null) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (!world.getBlockState(pos).is(Blocks.JUKEBOX)) return InteractionResult.PASS;
            ItemStack held = p.getMainHandItem();
            if (clean(held)) return InteractionResult.SUCCESS;
            CustomRecordData data = CustomRecordData.read(held);
            if (data == null || !recordable(held) || world.getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD)) return InteractionResult.PASS;
            if (!ServerPlayNetworking.canSend(p, SharedMusicPacket.TYPE)) { tell(p, "server_required"); return InteractionResult.FAIL; }
            if (!current.valid(data, world)) { tell(p, "missing_track"); return InteractionResult.FAIL; }
            if (current.sessions.size() >= 128) { tell(p, "busy"); return InteractionResult.FAIL; }
            if (!(world.getBlockEntity(pos) instanceof JukeboxBlockEntity box)) return InteractionResult.PASS;
            if (!current.readyToInsert(p, world, pos, data)) return InteractionResult.SUCCESS;
            // A new disc at the same position always ends the previous session first.
            current.sessions.removeIf(session -> {
                if (session.level != world || !session.pos.equals(pos)) return false;
                current.stopSession(session); return true;
            });
            box.setTheItem(held.copyWithCount(1));
            world.setBlock(pos, world.getBlockState(pos).setValue(JukeboxBlock.HAS_RECORD, true), 3);
            held.shrink(1);
            p.containerMenu.broadcastChanges();
            if (data.trackType().equals("VANILLA_DISC")) {
                box.getSongPlayer().play(world, VanillaJukeboxSongResolver.findById(world, Identifier.parse(data.trackValue())));
            } else current.sessions.add(new Session(world, pos, data));
            return InteractionResult.SUCCESS;
        });
    }

    private boolean valid(CustomRecordData record, ServerLevel level) {
        try {
            if (record.durationMillis() <= 0 || record.durationMillis() > 1_800_000L) return false;
            return switch (record.trackType()) {
                case "SHARED" -> SharedMusicFiles.validHash(record.trackValue()) && stored.contains(record.trackValue());
                case "VANILLA" -> record.trackValue().startsWith("track:")
                        && Identifier.parse(record.trackValue().substring(6)).getNamespace().equals("minecraft");
                case "VANILLA_DISC" -> VanillaJukeboxSongResolver.findById(level, Identifier.parse(record.trackValue())) != null;
                default -> false;
            };
        } catch (RuntimeException e) { return false; }
    }

    private void record(ServerPlayer p, RecordCustomDataPayload packet) {
        if (!ServerPlayNetworking.canSend(p, SharedMusicPacket.TYPE)) return;
        if (!clean(p.getMainHandItem()) || p.getMainHandItem().getCount() != 1) { tell(p, "hold_disc"); return; }
        if (packet.title().length() > 64 || packet.composer().length() > 64 || packet.trackValue().length() > 512) return;
        CustomRecordData data = new CustomRecordData(packet.trackType(), packet.trackValue(), packet.title(), packet.composer());
        if (data.trackType().equals("SHARED") || !valid(data, (ServerLevel) p.level())) { tell(p, "missing_track"); return; }
        commit(p, data);
    }
    private void commit(ServerPlayer p, CustomRecordData data) { commit(p, data, true); }
    private void commit(ServerPlayer p, CustomRecordData data, boolean notifyInChat) {
        commit(p, data, notifyInChat, p.getMainHandItem());
    }
    private void commit(ServerPlayer p, CustomRecordData data, boolean notifyInChat, ItemStack held) {
        data.writeTo(held); held.remove(DataComponents.JUKEBOX_PLAYABLE);
        held.set(DataComponents.CUSTOM_NAME, Component.translatable("music-delay-reducer.record.default_name"));
        List<Component> lore = new ArrayList<>();
        if (!data.title().isBlank()) lore.add(Component.literal(data.title()));
        if (!data.composer().isBlank()) lore.add(Component.literal(data.composer()));
        held.set(DataComponents.LORE, new ItemLore(lore)); p.containerMenu.broadcastChanges();
        if (notifyInChat) tell(p, "recorded");
        if (data.trackType().equals("SHARED")) announceTrack(data.trackValue());
    }

    private ItemStack recordingTarget(ServerPlayer player, String token) {
        RecordingReservation reservation = reservations.get(player.getUUID());
        return reservation != null && reservation.token.equals(token) ? reservation.find(player) : null;
    }

    private void releaseReservation(ServerPlayer player, String token) {
        RecordingReservation reservation = reservations.get(player.getUUID());
        if (reservation != null && reservation.token.equals(token)) releaseReservation(player);
    }

    private void releaseReservation(ServerPlayer player) {
        RecordingReservation reservation = reservations.remove(player.getUUID());
        if (reservation != null) reservation.clear(player);
    }

    private void cancelRecording(ServerPlayer player, String token, String reason) {
        RecordingReservation reservation = reservations.get(player.getUUID());
        if (reservation == null || !reservation.token.equals(token)) return;
        uploads.remove(player.getUUID()); releaseReservation(player);
        send(player, SharedMusicPacket.simple("record_cancelled", token, reason));
    }

    private void tickReservations() {
        for (var entry : Map.copyOf(reservations).entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            RecordingReservation reservation = entry.getValue();
            if (player == null) { reservations.remove(entry.getKey()); uploads.remove(entry.getKey()); continue; }
            if (reservation.find(player) == null) cancelRecording(player, reservation.token, "recording_cancelled");
            else if (!uploads.containsKey(entry.getKey()) && ticks - reservation.created > 2400)
                cancelRecording(player, reservation.token, "transfer_failed");
        }
    }

    private void receive(ServerPlayer p, SharedMusicPacket packet) {
        if (!ServerPlayNetworking.canSend(p, SharedMusicPacket.TYPE)) return;
        UUID id = p.getUUID();
        switch (packet.action()) {
            case "cache_identity" -> {
                if (cacheIdentity != null) send(p, SharedMusicPacket.simple("cache_identity", cacheIdentity, ""));
            }
            case "prepare_status" -> {
                var known = preparations.get(id);
                ClientPreparation status = known == null ? null : known.get(packet.id());
                if (status != null && status.token.equals(packet.value()) && allowed(p, packet.id())
                        && packet.number() >= -1 && packet.number() <= 100) {
                    status.percent = (int) packet.number(); status.updated = ticks;
                }
            }
            case "reserve_record" -> {
                try { UUID.fromString(packet.id()); } catch (IllegalArgumentException e) { return; }
                if (reservations.containsKey(id) || uploads.containsKey(id)) {
                    send(p, SharedMusicPacket.simple("record_cancelled", packet.id(), "busy")); return;
                }
                int slot = packet.total();
                if (slot < 0 || slot >= p.getInventory().getContainerSize()) return;
                ItemStack target = p.getInventory().getItem(slot);
                if (!clean(target) || target.getCount() != 1) {
                    send(p, SharedMusicPacket.simple("record_cancelled", packet.id(), "hold_disc")); return;
                }
                reservations.put(id, new RecordingReservation(target, packet.id(), ticks));
                p.containerMenu.broadcastChanges();
                send(p, SharedMusicPacket.simple("record_reserved", packet.id(), ""));
            }
            case "record_cancel" -> {
                RecordingReservation reservation = reservations.get(id);
                if (reservation != null && reservation.token.equals(packet.id())) {
                    uploads.remove(id); releaseReservation(p);
                }
            }
            case "record_builtin" -> {
                ItemStack target = recordingTarget(p, packet.id());
                if (target == null) { cancelRecording(p, packet.id(), "recording_cancelled"); return; }
                boolean disc = packet.value().startsWith("disc:");
                CustomRecordData data = new CustomRecordData(disc ? "VANILLA_DISC" : "VANILLA",
                        disc ? packet.value().substring(5) : packet.value(), packet.title(), packet.composer(), packet.number());
                if (!valid(data, (ServerLevel) p.level())) { cancelRecording(p, packet.id(), "missing_track"); return; }
                commit(p, data, true, target); releaseReservation(p);
                send(p, SharedMusicPacket.simple("record_complete", packet.id(), ""));
            }
            case "upload_begin" -> {
                if (recordingTarget(p, packet.value()) == null) { rejectUpload(p, packet, "recording_cancelled"); return; }
                if (!SharedMusicFiles.validHash(packet.id()) || packet.total() <= 0 || packet.total() > SharedMusicPacket.MAX_FILE
                        || packet.number() <= 0 || packet.number() > 1_800_000L) { rejectUpload(p, packet, "transfer_failed"); return; }
                if (uploads.size() >= 4 || uploads.containsKey(id) || ticks < nextUpload.getOrDefault(id, 0L)) { rejectUpload(p, packet, "busy"); return; }
                nextUpload.put(id, ticks + 100);
                uploads.put(id, new Upload(p, packet, ticks));
                send(p, SharedMusicPacket.simple("upload_ready", packet.id(), packet.value()));
            }
            case "upload_chunk" -> {
                Upload upload = uploads.get(id);
                if (upload == null || upload.saving || !upload.hash.equals(packet.id()) || !upload.token.equals(packet.value())) return;
                if (packet.number() != upload.received || packet.data().length == 0
                        || upload.received + packet.data().length > upload.bytes.length) {
                    uploads.remove(id); rejectUpload(p, packet, "transfer_failed"); return;
                }
                System.arraycopy(packet.data(), 0, upload.bytes, upload.received, packet.data().length);
                upload.received += packet.data().length; upload.touched = ticks;
                int percent = (int) (100L * upload.received / upload.bytes.length);
                if (percent != upload.lastPercent) {
                    upload.lastPercent = percent;
                    send(p, new SharedMusicPacket("upload_progress", upload.hash, upload.token, "", "",
                            BlockPos.ZERO, upload.received, upload.bytes.length, new byte[0]));
                }
                if (upload.received == upload.bytes.length) {
                    upload.saving = true;
                    try { io.execute(() -> {
                        try {
                            SharedMusicFiles.store(root, upload.hash, upload.bytes, 1024L * 1024 * 1024);
                            server.execute(() -> {
                                if (!alive) return;
                                stored.add(upload.hash);
                                if (uploads.remove(id, upload) && server.getPlayerList().getPlayer(id) == p) {
                                    ItemStack target = recordingTarget(p, upload.token);
                                    if (target != null) {
                                        commit(p, new CustomRecordData("SHARED", upload.hash, upload.title, upload.composer, upload.duration), false, target);
                                        releaseReservation(p);
                                        send(p, SharedMusicPacket.simple("upload_complete", upload.hash, upload.token));
                                    } else failUpload(p, upload, "recording_cancelled");
                                }
                            });
                        } catch (Exception e) {
                            MusicDelayReducer.LOGGER.warn("Cannot store shared music " + upload.hash, e);
                            server.execute(() -> { if (alive && uploads.remove(id, upload)) failUpload(p, upload, "transfer_failed"); });
                        }
                    }); } catch (RejectedExecutionException e) {
                        uploads.remove(id, upload);
                        failUpload(p, upload, "busy");
                    }
                }
            }
            case "cancel_download" -> {
                Download download = downloads.get(id);
                if (download != null && download.hash.equals(packet.id())) downloads.remove(id);
                LoadRequest request = loading.get(id);
                if (request != null && request.hash.equals(packet.id())) { request.cancelled = true; loading.remove(id); }
                send(p, SharedMusicPacket.simple("cancelled", packet.id(), ""));
            }
            case "prioritize" -> {
                Download download = downloads.get(id);
                if (download != null && download.hash.equals(packet.id())) download.background = false;
                LoadRequest request = loading.get(id);
                if (request != null && request.hash.equals(packet.id())) request.background = false;
            }
            case "download" -> {
                String hash = packet.id();
                if (!SharedMusicFiles.validHash(hash) || !stored.contains(hash) || !allowed(p, hash)) {
                    send(p, SharedMusicPacket.simple("failed", hash, "")); return;
                }
                if (downloads.containsKey(id) || loading.containsKey(id)) {
                    send(p, SharedMusicPacket.simple("retry", hash, "")); return;
                }
                boolean background = packet.value().equals("prefetch");
                // Keep one transfer slot available for actual playback.
                if (downloads.size() + loading.size() >= (background ? 3 : 4)) {
                    send(p, SharedMusicPacket.simple("retry", hash, "")); return;
                }
                LoadRequest request = new LoadRequest(hash, background); loading.put(id, request);
                try { io.execute(() -> {
                    if (request.cancelled) return;
                    try {
                        Path path = SharedMusicFiles.file(root, hash);
                        long size = Files.size(path);
                        if (size <= 0 || size > SharedMusicPacket.MAX_FILE) throw new IllegalStateException("Invalid stored audio size");
                        byte[] bytes = Files.readAllBytes(path);
                        if (!SharedMusicFiles.hash(bytes).equals(hash)) throw new IllegalStateException("Stored audio is corrupt");
                        server.execute(() -> {
                            if (!loading.remove(id, request)) return;
                            if (alive && server.getPlayerList().getPlayer(id) == p) {
                                if (allowed(p, hash)) downloads.put(id, new Download(hash, bytes, request.background));
                                else send(p, SharedMusicPacket.simple("failed", hash, ""));
                            }
                        });
                    } catch (Exception e) {
                        MusicDelayReducer.LOGGER.warn("Cannot load shared music " + hash, e);
                        server.execute(() -> { if (loading.remove(id, request) && alive) send(p, SharedMusicPacket.simple("failed", hash, "")); });
                    }
                }); } catch (RejectedExecutionException e) {
                    loading.remove(id, request);
                    send(p, SharedMusicPacket.simple("retry", hash, ""));
                }
            }
            default -> { }
        }
    }

    // Only opaque content IDs are advertised: no track names, composers or notifications.
    private void announceTrack(String hash) {
        if (!stored.contains(hash)) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!ServerPlayNetworking.canSend(player, SharedMusicPacket.TYPE)) continue;
            var known = offers.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
            Long previous = known.get(hash);
            if (previous != null && ticks - previous < 100) continue;
            known.remove(hash);
            if (known.size() >= MAX_OFFERS) {
                String oldest = known.keySet().iterator().next(); known.remove(oldest);
                var states = preparations.get(player.getUUID());
                if (states != null) states.remove(oldest);
            }
            known.put(hash, ticks);
            send(player, SharedMusicPacket.simple("prefetch", hash, preparation(player, hash, false).token));
        }
    }

    private void announceInventoryTracks() {
        Set<String> hashes = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && hashes.size() < MAX_OFFERS; slot++) {
                CustomRecordData record = CustomRecordData.read(inventory.getItem(slot));
                if (record != null && record.trackType().equals("SHARED") && stored.contains(record.trackValue()))
                    hashes.add(record.trackValue());
            }
        }
        for (Session session : sessions) {
            if (hashes.size() >= MAX_OFFERS) break;
            if (session.record.trackType().equals("SHARED")) hashes.add(session.record.trackValue());
        }
        hashes.forEach(this::announceTrack);
    }

    private ClientPreparation preparation(ServerPlayer player, String hash, boolean retryFailure) {
        var known = preparations.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
        ClientPreparation status = known.get(hash);
        if (status == null || (retryFailure && status.percent < 0)) {
            if (known.size() >= MAX_OFFERS) known.remove(known.keySet().iterator().next());
            status = new ClientPreparation(); known.put(hash, status);
        }
        return status;
    }

    private Set<ServerPlayer> nearbyListeners(ServerPlayer owner, PendingInsertion wait) {
        int radius = Math.clamp(ModConfig.get().jukeboxDetectionRadius, 1, 512);
        double radiusSq = (double) radius * radius;
        Set<ServerPlayer> listeners = new HashSet<>();
        listeners.add(owner);
        for (ServerPlayer player : PlayerLookup.tracking(wait.level, wait.pos)) {
            if (player.level() == wait.level && player.blockPosition().distSqr(wait.pos) <= radiusSq
                    && ServerPlayNetworking.canSend(player, SharedMusicPacket.TYPE)) listeners.add(player);
        }
        return listeners;
    }

    private int preparationPercent(ServerPlayer owner, PendingInsertion wait, boolean retryFailure) {
        int minimum = 100;
        for (ServerPlayer player : nearbyListeners(owner, wait)) {
            String hash = wait.record.trackValue();
            ClientPreparation status = preparation(player, hash, retryFailure);
            int percent = ticks - status.updated <= 200 ? status.percent : 0;
            minimum = Math.min(minimum, percent);
            if (ticks - status.requested >= 20) {
                status.requested = ticks;
                send(player, SharedMusicPacket.simple("prepare_track", hash, status.token));
            }
        }
        return minimum;
    }

    private void sendWait(ServerPlayer owner, PendingInsertion wait, int percent) {
        if (wait.lastPercent == percent && (percent < 0 || percent == 100 || ticks - wait.lastSent < 20)) return;
        wait.lastPercent = percent; wait.lastSent = ticks;
        send(owner, new SharedMusicPacket("wait_progress", wait.id, "", "", "", wait.pos, percent, 0, new byte[0]));
    }

    private boolean readyToInsert(ServerPlayer owner, ServerLevel level, BlockPos pos, CustomRecordData record) {
        if (!record.trackType().equals("SHARED")) return true;
        PendingInsertion wait = pendingInsertions.get(owner.getUUID());
        if (wait == null || wait.level != level || !wait.pos.equals(pos) || !wait.record.equals(record)) {
            if (wait == null && pendingInsertions.size() >= 128) { tell(owner, "busy"); return false; }
            wait = new PendingInsertion(owner, level, pos, record, ticks);
            pendingInsertions.put(owner.getUUID(), wait);
        }
        int percent = preparationPercent(owner, wait, true);
        if (percent == 100) {
            pendingInsertions.remove(owner.getUUID());
            send(owner, SharedMusicPacket.simple("wait_clear", wait.id, ""));
            return true;
        }
        sendWait(owner, wait, percent);
        return false;
    }

    private void tickPendingInsertions() {
        if (ticks % 5 != 0) return;
        for (var it = pendingInsertions.entrySet().iterator(); it.hasNext();) {
            var entry = it.next(); PendingInsertion wait = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner == null) { it.remove(); continue; }
            boolean cancelled = owner.level() != wait.level || owner.blockPosition().distSqr(wait.pos) > 64
                    || owner.getInventory().getSelectedSlot() != wait.slot || !ItemStack.matches(owner.getMainHandItem(), wait.expected);
            if (!cancelled) {
                if (!wait.level.isLoaded(wait.pos)) cancelled = true;
                else {
                    var state = wait.level.getBlockState(wait.pos);
                    cancelled = !state.is(Blocks.JUKEBOX) || state.getValue(JukeboxBlock.HAS_RECORD);
                }
            }
            if (cancelled) {
                send(owner, SharedMusicPacket.simple("wait_clear", wait.id, "")); it.remove(); continue;
            }
            int percent = ticks - wait.created >= 2400 ? -1 : preparationPercent(owner, wait, false);
            sendWait(owner, wait, percent);
            // Readiness never inserts a disc automatically; another click is required.
            if (percent < 0) it.remove();
        }
    }

    private boolean allowed(ServerPlayer player, String hash) {
        for (var entry : pendingInsertions.entrySet()) {
            PendingInsertion wait = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner != null && wait.record.trackValue().equals(hash) && nearbyListeners(owner, wait).contains(player)) return true;
        }
        var known = offers.get(player.getUUID());
        if (known != null && ticks - known.getOrDefault(hash, -OFFER_LIFETIME) < OFFER_LIFETIME) return true;
        return sessions.stream().anyMatch(s -> s.record.trackType().equals("SHARED") && s.record.trackValue().equals(hash)
                && s.level == player.level() && PlayerLookup.tracking(s.level, s.pos).contains(player));
    }
    private void stopSession(Session s) {
        for (UUID id : s.listeners) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) send(p, new SharedMusicPacket("stop", s.id, "", "", "", s.pos, 0, 0, new byte[0]));
        }
    }
    private void tick() {
        ticks++;
        tickReservations();
        if (ticks % 100 == 1) announceInventoryTracks();
        tickPendingInsertions();
        uploads.entrySet().removeIf(e -> {
            if (ticks - e.getValue().touched < 2400) return false;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) failUpload(p, e.getValue(), "transfer_failed"); return true;
        });
        for (var it = downloads.entrySet().iterator(); it.hasNext();) {
            var entry = it.next(); ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey()); Download d = entry.getValue();
            if (p == null || !ServerPlayNetworking.canSend(p, SharedMusicPacket.TYPE)) { it.remove(); continue; }
            for (int n = 0; n < (d.background ? 1 : 2) && d.offset < d.bytes.length; n++) {
                int end = Math.min(d.bytes.length, d.offset + SharedMusicPacket.CHUNK);
                send(p, new SharedMusicPacket("download_chunk", d.hash, "", "", "", BlockPos.ZERO,
                        d.offset, d.bytes.length, Arrays.copyOfRange(d.bytes, d.offset, end)));
                d.offset = end;
            }
            if (d.offset == d.bytes.length) it.remove();
        }
        for (var it = sessions.iterator(); it.hasNext();) {
            Session s = it.next(); long elapsed = (s.level.getGameTime() - s.start) * 50;
            boolean invalid = elapsed >= s.record.durationMillis();
            if (s.level.isLoaded(s.pos)) {
                var state = s.level.getBlockState(s.pos);
                invalid |= !state.is(Blocks.JUKEBOX) || !state.hasProperty(JukeboxBlock.HAS_RECORD) || !state.getValue(JukeboxBlock.HAS_RECORD);
                if (s.level.getBlockEntity(s.pos) instanceof JukeboxBlockEntity box)
                    invalid |= !s.record.equals(CustomRecordData.read(box.getTheItem()));
                else invalid = true;
            }
            if (invalid) { stopSession(s); it.remove(); continue; }
            // Initial play is sent on the first tick; later packets only maintain synchronisation.
            if (s.announced && ticks % 20 != 0) continue;
            s.announced = true;
            Set<UUID> present = new HashSet<>();
            for (ServerPlayer p : PlayerLookup.tracking(s.level, s.pos)) {
                if (!ServerPlayNetworking.canSend(p, SharedMusicPacket.TYPE)) continue;
                present.add(p.getUUID());
                send(p, new SharedMusicPacket("play", s.id, s.record.trackType() + "|" + s.record.trackValue(),
                        cacheLabel(s.record.title()), cacheLabel(s.record.composer()), s.pos, elapsed, 0, new byte[0]));
            }
            for (UUID id : s.listeners) if (!present.contains(id)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) send(p, new SharedMusicPacket("stop", s.id, "", "", "", s.pos, 0, 0, new byte[0]));
            }
            s.listeners.clear(); s.listeners.addAll(present);
        }
    }
}
