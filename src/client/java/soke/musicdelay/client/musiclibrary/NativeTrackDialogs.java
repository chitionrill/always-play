package soke.musicdelay.client.musiclibrary;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.sdl.SDLError;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.lwjgl.sdl.SDLDialog.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Native asynchronous dialogs. Keep native resources alive until SDL replies. */
public final class NativeTrackDialogs {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<Long, Request> PENDING = new ConcurrentHashMap<>();

    // One callback for the lifetime of the client; userdata identifies each request.
    private static final SDL_DialogFileCallback CALLBACK = SDL_DialogFileCallback.create(
            (userdata, filelist, filter) -> {
                Request request = PENDING.remove(userdata);
                if (request == null) return;
                Path selected = null;
                try {
                    if (filelist == NULL) {
                        System.err.println("Always Play: file dialog failed: " + SDLError.SDL_GetError());
                    } else {
                        long first = memGetAddress(filelist);
                        if (first != NULL) selected = Path.of(memUTF8(first));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    request.close();
                }
                Path result = selected;
                Minecraft.getInstance().execute(() -> request.onResult.accept(result));
            });

    private NativeTrackDialogs() {}

    public static void pickAudio(Consumer<Path> onResult) {
        show(false, null, "Select audio track", onResult);
    }

    public static void pickFolder(Path initialDirectory, String title, Consumer<Path> onResult) {
        show(true, initialDirectory, title, onResult);
    }

    private static void show(boolean folder, Path initialDirectory, String title, Consumer<Path> onResult) {
        Minecraft.getInstance().execute(() -> {
            long id = NEXT_ID.incrementAndGet();
            Request request = new Request(onResult);
            try {
                request.properties = SDL_CreateProperties();
                if (request.properties == 0) throw new IllegalStateException(SDLError.SDL_GetError());
                check(SDL_SetStringProperty(request.properties, SDL_PROP_FILE_DIALOG_TITLE_STRING, title));
                check(SDL_SetPointerProperty(request.properties, SDL_PROP_FILE_DIALOG_WINDOW_POINTER,
                        Minecraft.getInstance().getWindow().handle()));
                check(SDL_SetBooleanProperty(request.properties, SDL_PROP_FILE_DIALOG_MANY_BOOLEAN, false));
                if (initialDirectory != null) {
                    check(SDL_SetStringProperty(request.properties, SDL_PROP_FILE_DIALOG_LOCATION_STRING,
                            initialDirectory.toAbsolutePath().toString()));
                }
                if (!folder) {
                    request.filterName = memUTF8("Audio files (.wav, .mp3, .ogg, .flac)");
                    request.filterPattern = memUTF8("wav;mp3;ogg;flac");
                    request.filters = SDL_DialogFileFilter.calloc(1);
                    request.filters.get(0).name(request.filterName).pattern(request.filterPattern);
                    check(SDL_SetPointerProperty(request.properties, SDL_PROP_FILE_DIALOG_FILTERS_POINTER,
                            request.filters.address()));
                    check(SDL_SetNumberProperty(request.properties, SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1));
                }
                PENDING.put(id, request);
                SDL_ShowFileDialogWithProperties(folder ? SDL_FILEDIALOG_OPENFOLDER : SDL_FILEDIALOG_OPENFILE,
                        CALLBACK, id, request.properties);
            } catch (Exception e) {
                PENDING.remove(id);
                request.close();
                e.printStackTrace();
                onResult.accept(null);
            }
        });
    }

    private static void check(boolean success) {
        if (!success) throw new IllegalStateException(SDLError.SDL_GetError());
    }

    private static final class Request {
        final Consumer<Path> onResult;
        int properties;
        SDL_DialogFileFilter.Buffer filters;
        ByteBuffer filterName;
        ByteBuffer filterPattern;

        Request(Consumer<Path> onResult) {
            this.onResult = onResult;
        }

        void close() {
            if (properties != 0) SDL_DestroyProperties(properties);
            properties = 0;
            if (filters != null) filters.free();
            filters = null;
            memFree(filterName);
            filterName = null;
            memFree(filterPattern);
            filterPattern = null;
        }
    }
}
