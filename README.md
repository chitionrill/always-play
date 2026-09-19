# Always Play

*(formerly Music Delay Reducer)*

By default, this mod reduces the pause between tracks from 10–20 minutes to just 2–5 seconds, allowing you to switch tracks instantly and play your own music alongside or instead of the original Minecraft music.

## Features

### Delay Control
- Default interval: 2–5 seconds.
- Configurable via Mod Menu: from 0 seconds to 20 minutes.
- Startup Fade-In: music stays silent for a moment after launching the game, then fades in smoothly instead of blasting instantly. Toggleable, with adjustable delay (1–5 sec).

### Custom Music
- Load your own tracks (WAV, MP3, OGG, FLAC) by dropping files into the `tracks` folder or using the in-game "Add Track" button.
- Three playback modes: Vanilla only, Custom only, or Both mixed together.
- Automatic volume normalization, so quiet and loud files all sound consistent.
- Smooth crossfade between tracks — toggleable, with adjustable duration.
- Three track order modes: In Order, Random (No Repeat), and fully Random.
- "Now Playing" toast for custom tracks, just like vanilla's own notification.

### Track History & Navigation
- Skip Forward / Skip Backward keybinds (rebindable, default: Right Arrow / Left Arrow).
- Remembers up to 10 previously played tracks — go back to replay a song you liked, or skip forward through ones you've already heard.
- Skip Delay setting (0–5 sec) to control the pause when you switch tracks manually.
- Volume Up/Down keybinds (default: Up/Down arrows), 5% per press, with acceleration on hold.
- All hotkeys are organized under a dedicated "Music Delay Reducer" category in your Controls settings.

### Other
- Music restarts fresh when joining a world instead of continuing whatever was already playing (toggleable).
- Every setting in Mod Menu has a tooltip explaining what it does.
- Scrollable, resizable settings screen.
- Language support: English, Russian, Ukrainian. (Want more languages? Let me know in the comments!)

## Music library
- In-game music browser and playlists.
- Browse vanilla background music, music discs and your own files.

## Shared custom records (network preview)

This build is a testing candidate, not the final 1.2.3 release.

Install the same build on the server/host and on every player who wants to hear
or insert custom records. The ordinary client music features still work without
the server component, but recording is disabled on unsupported servers.

Rename one music disc to `Clean`, use it on a jukebox, select a track and confirm.
Keep that disc selected until the server confirms recording. Local audio is
uploaded in small chunks and stored with the world. A disc stores a SHA-256 track
identifier, not the author's local path. You can give the disc to another player;
it keeps working after its author disconnects. Recipients download the track
when they track a playing jukebox, verify its checksum and cache it locally.
Late listeners start near the current playback position after downloading.
This is approximate synchronization, not sample-accurate synchronization.

Vanilla background tracks refer to Minecraft resources, so they need no audio
upload. Different resource packs can provide different audio for the same ID.
Existing vanilla disc songs continue using Minecraft's native playback.

Current limits:
- 32 MiB per uploaded track, 30 minutes per recorded background/custom track.
- 1 GiB audio storage per world; 512 MiB client cache. Full storage reports an
  error rather than deleting tracks that existing records may need.
- Up to four simultaneous uploads and four downloads on the server; two 24 KiB
  chunks per tick per outgoing transfer; up to 16 custom audio sources per client.
- Audio belongs to the world directory `always-play-audio`; include this folder
  in backups and when moving the world to another server. Client downloads live
  in `always-play-audio-cache` in the game directory.
- Old local-file records must be recorded again to upload their audio. Old
  ambient records containing a sound event instead of a track also need recording again.
- After a server/world restart, eject and reinsert a disc to restart it.
- Active playback is server-timed: returning after leaving chunk range joins the
  current position rather than preserving a private pause for each listener.
- Failed downloads can be retried by leaving and rejoining the world.

Before publishing as stable, test LAN and a dedicated server with two independent
clients, including transfer to a player who has no copy of the original file.

## About this mod

Personally, I just wanted a mod that keeps the music playing constantly, without having to wait 20 minutes for Minecraft to finally "grace us" with the next track — and eventually wanted to bring my own music into the mix too. That's why I created this and decided to share it with you. I know there are already mods that do this, but unfortunately, they are either quite slow to update or have been abandoned entirely.

## License

This project's own source code is licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)** license. See the [LICENSE](LICENSE) file for details.

You are free to share and adapt this project for non-commercial purposes, as long as you give appropriate credit and distribute your contributions under the same license.

This mod also bundles unmodified third-party audio codec libraries (for MP3/OGG/FLAC support). See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for their versions and licenses — the CC BY-NC-SA terms above cover this project's own code only, not those libraries.
