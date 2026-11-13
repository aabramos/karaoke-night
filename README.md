# Karaoke Night

Party karaoke for Android TV (built for the NVIDIA Shield). Also runs on Android phones and tablets.

## Features

- Karaoke-only YouTube search, with quick picks (Top hits, 80s, 90s, Duets…) and voice search
- Song queue with a "Next up" countdown between singers
- Guests add songs from their phones by scanning a QR code, without interrupting the singer
- Solo and duet modes, a saved players list, and a fun score (it doesn't listen to the singer)
- Hall of fame (solo and duets, tonight and all time), 43 achievements (scores, emojis from phones, special dates such as Christmas, Carnival and Halloween), 18 genres to collect, and a 🌙 "Close the night" awards show
- 🎲 Surprise me: a random song everyone knows
- Party country: quick picks and search ranking for any country
- Cheers from the phones that float up the edge of the TV
- Bossa nova lobby music between songs, and three volume sliders: songs, sound effects, lobby music (10% by default)
- No YouTube API key needed (an optional key makes search more reliable)
- Keeps going when the internet or Wi-Fi drops (see [Connection problems](#connection-problems))

**Scoring:** a song sung to the end scores 65 to 100. Stopping early scores 0 to 65, higher the further you got. Stopping in the first 10% gives no score.

## Party checklist

1. Open **Karaoke Night**. The queue and the QR code are ready.
2. For guests: select the QR card and press **OK** for a big code.
3. Pick a song, choose **Solo** or **Duet**, pick the player(s), press **Sing now!**

The queue is saved. If the app closes, everything is still there when it reopens.

## Remote controls

| Where | Button | Does |
|---|---|---|
| Lobby | Arrows / OK | Move, pick a song, open a queued song |
| Lobby | 🎙 or the remote's search button | Voice search |
| Lobby | QR card, then OK | Big code for phones |
| Lobby | 🌍 | Party country |
| Lobby | 🏆 | Hall of fame and achievements. ▼ scrolls a list, OK on a name shows their achievements (▲▼ scroll) |
| Lobby | 🌙 | Close the night: awards show (OK next, ◀ back, BACK leave) |
| Lobby | 🎵 | Volume sliders, lobby music on/off |
| Lobby | ℹ️ | About |
| Lobby | BACK twice | Exit |
| Who's singing? | | Mode (Solo/Duet), pick players (OK again un-picks), ‹ › more, 🎲 Random name, type a new player, 🗑 Remove. Then **Sing now!** or **Add to queue** |
| Next up | OK / ▶ / BACK | Start now / move singer to the end / back to the lobby |
| Singing | ◀ ▶ | Song volume |
| Singing | OK | Pause / resume |
| Singing | ▼ / ▲ | Queue panel / singer info |
| Singing | BACK | Menu: keep singing, score me, skip, lobby |
| Score | OK | Continue (it also continues by itself) |

If songs are queued and nobody touches the remote for 30 seconds, the party starts by itself.
On a touchscreen, tap instead: the video opens a controls bar, and pop-ups have a **← Back** button.

## Guests' phones

Guests scan the QR code (same Wi-Fi as the TV), enter their name once, search, and tap **+**.

From the top, the page shows: who's singing (with cheer buttons during a song), tonight's top 3, the queue, their name, and the search. A **🔍 Add a song** button jumps to the search. After adding, the phone says your place in line. A phone can remove only its own waiting songs (✕, then confirm). Only the TV can remove players.

Guest Wi-Fi networks often block devices from seeing each other. If the page won't open, use the main network.

**Security:** the page answers only devices on the local network, and only with the secret key in the QR code. The key changes after 12 hours (between parties). Requests, searches, songs per singer (3) and cheers are rate-limited.

## Connection problems

| What happens | What the app does |
|---|---|
| The TV loses its internet | A banner says so on the TV and on phones. Searches say why they failed. The next singer waits on "Next up" (📡) instead of being skipped. A song that stalls continues from where it stopped once the internet is back, and failed searches run again by themselves. |
| A video won't start by itself | After a few seconds the app presses play, taps the player, then reloads it. If it still won't start, another version of the song plays. |
| A video's owner blocks it | Another karaoke version of the same song plays, and the blocked video stops showing up. |
| A phone can't reach the TV | A red bar on the phone with a **Try again** button. Actions explain the problem instead of failing silently. The phone reconnects by itself. |
| The TV gets a new Wi-Fi address | The QR code updates. Guests scan it again. |
| An old party link | The phone says to scan the QR code on the TV again. |

## YouTube API key (optional)

Without a key, the app searches the way YouTube's website does (like NewPipe). This has no daily limit, but it's against YouTube's terms and can break if YouTube changes its site. Playback always uses YouTube's official embedded player.

With a key, search uses the official YouTube Data API (about 100 searches a day; results are cached for 24 hours). It falls back to key-less search when the quota runs out.

1. In <https://console.cloud.google.com/>, create a project and enable **YouTube Data API v3**.
2. Create an API key. Restrict it to Android apps (package `com.karaoketv` and your SHA-1 from `./gradlew signingReport`) and to YouTube Data API v3.
3. Add `YOUTUBE_API_KEY=AIza…` to `local.properties` and rebuild.

Don't put a key in a public repository's builds: anyone could extract it from the APK.

## Build and install

**In the cloud (GitHub):**

1. Upload this folder to a GitHub repository. GitHub Actions builds the APK (about 5 minutes) and publishes it under **Releases**.
2. On the Shield, install **Downloader** and enter
   `https://github.com/YOUR-NAME/YOUR-REPO/releases/latest/download/KaraokeNight.apk`.
   Enter the same address again to update.
3. Optional secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` sign every build with your key, so updates install over the old version without losing the hall of fame.

**With Android Studio:** open the folder and run `./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`. To sign with your own key, add a `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

**With adb:** enable Network debugging on the Shield (Developer options), then `adb connect <shield-ip>:5555` and `adb install -r <apk>`.

Requirements: Android Gradle Plugin 9.5, Gradle 9.6, JDK 17. Min Android 7.0.

## Built for the Shield's 2 GB

- One main screen. The YouTube player exists only during a party and is replaced every 6 songs.
- Capped caches, small thumbnails, streamed search results and lobby music.
- Every saved list is capped and stored compactly (a typical party uses under 20 KB).
- No native code (works on 32-bit and 64-bit), no heavy libraries, R8-shrunk release builds.
- Debug builds include LeakCanary and StrictMode.

## Customise

| What | Where |
|---|---|
| Score range and spread | `Scores.kt`, `Judge` |
| How much cheers move the score | `Scores.kt`, `Judge.CROWD_POINTS`, and `Party.kt`, `REACTION_VOTES` |
| Judge comments | `Scores.kt`, `Judge.lines` |
| Rare judges and emoji crowd | `Scores.kt`, `Extras.CHANCE` and `MIN_SCORE` |
| Quick picks | `Countries.kt`, `INTERNATIONAL_PICKS` and `HAND` |
| Surprise me songs | `Surprise.kt`, `WORLD` and `LOCAL` |
| Achievements | `Achievements.kt` |
| Genres | `Genres.kt` |
| Lobby music | `res/raw/lobby.ogg`; levels in `Audio.kt`, `LobbyMusic` |
| Random stage names | `MainActivity.kt`, `STAGE_NAMES` |
| Autostart delay, finale timing | `MainActivity.kt`, `AUTOSTART_MS`, `FINALE_LEAD_S`, `FINALE_MS` |
| Max songs per singer | `Party.kt`, `MAX_PER_SINGER` |

## Troubleshooting

- **The app stopped:** open it again. It shows the error; take a photo, then press **Try again**.
- **No QR code:** the TV isn't on Wi-Fi or Ethernet.
- **Voice search:** allow the microphone the first time. The remote's own mic button opens Google Assistant, not this app.
- **Subtitles:** the app turns YouTube captions off. Captions burned into the video can't be removed.
- **"YouTube search is unavailable":** YouTube changed something. Add an API key.
- **Ads and suggestions:** YouTube may show ads. Suggestions over a paused or ending video are covered by the app.
- **Reset blocked videos:** clear the app's storage in Android settings (this also clears scores).

Playback uses [android-youtube-player](https://github.com/PierfrancescoSoffritti/android-youtube-player). Lobby music and sound effects were made for this app.
