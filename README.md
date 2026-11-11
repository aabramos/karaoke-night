# Karaoke Night: a party karaoke app for Android TV / NVIDIA Shield

A karaoke app for the big screen, built for parties:

- **Karaoke-only search** on YouTube, with quick picks (Top hits, Portuguese, 80s, 90s, Duets, Party…)
- **Party country**: pick any country (all of them are listed) and the quick picks switch to music
  from there (Fado and Pimba for Portugal, Sertanejo and MPB for Brazil…), while YouTube ranks every
  search for that country and language. Guests' phones follow automatically
- **Song queue** with singer names. Songs play one after another with a **"Next up: MARIA!"** countdown in between
- **Guests add songs from their phones** by scanning the QR code on the TV, **without interrupting the current singer**
- **Quiet lobby music** while people choose songs; it fades out when a song starts and back in afterwards
- **Music volume on the remote** (◀ ▶), separate from the TV volume, to balance the backing track against your mics
- **The judge**: drumroll, count-up, stars, a verdict, applause, and confetti + fanfare for 95+
- **Hall of fame**: podium and top 10 for tonight and all time. Scores are saved automatically under each singer's name
- **Works without a YouTube API key** (optional key for extra reliability, see below)
- **Never stalls on a blocked video**: if a video's owner blocks playback outside YouTube, the app
  finds another karaoke version of the same song for the same singer
- Made for the TV, and also runs full screen on Android phones and tablets

> The score is just for fun: it doesn't listen to the singer. Most scores land between ~78 and ~92,
> 95+ happens about 1 song in 20, and a perfect 100 is very rare. Stopping a song early lowers the
> score; stopping in the first quarter gives none.

---

## Party-night checklist (about 1 minute)

1. Open **Karaoke Night** on the Shield. Songs, the queue and the phone QR code are already there. No setup.
2. Want guests to add songs? Select the QR card and press **OK** for a big code everyone can scan from the sofa.
3. Pick a song → choose a name (or **🎲 Surprise name**, no typing) → **🎤 Sing now!**

That's it. The queue is saved, so if someone exits the app by accident, everything is still there when it reopens.

## How a party goes

1. **Lobby**: browse the quick picks, type, or press **🎙** and say the song. Pick one → choose who's singing
   (recent singers are one click away). Guests can scan the QR code on the right and add songs too.
2. Press **▶ START** (or just wait: if songs are queued and nobody touches the remote for 30 s, the party starts by itself).
3. **Next up**: the singer's name appears with a 10-second countdown while the video loads.
4. **Singing**: full-screen video. Press **▼** to open the queue panel on the right and add songs while the song keeps playing.
5. **Score**: drumroll, score, rank of the night, then straight on to the next singer.

## Remote controls

| Where | Button | Does |
|---|---|---|
| Lobby | ▲ ▼ ◀ ▶ / OK | Move around, pick a song, open a queued song (sing next / remove) |
| Lobby | 🎙 button (or the remote's search button) | Voice search: say a song or artist |
| Lobby | QR card → OK | Big join code for guests' phones |
| Lobby | 🌍 / flag button | Party country: every country, type a few letters to find one |
| Lobby | 🏆 / 🎵 buttons | Hall of fame / lobby music on-off |
| Lobby | BACK twice | Exit the app |
| Next up | OK | Start now |
| Next up | ▶ | "Not ready": move this singer to the end of the queue |
| Next up | BACK | Back to the lobby (the singer keeps their place) |
| Singing | ◀ ▶ | Music volume down / up (remembered) |
| Singing | OK / ⏯ | Pause / resume |
| Singing | ▼ | Queue panel: see who's next, add songs without stopping the music |
| Singing | ▲ | Show singer and "up next" again |
| Singing | BACK | Menu: keep singing / score me / skip / lobby |
| Score | OK | Continue (it also continues by itself) |

**On a phone or tablet** everything works by touch: tap **Next up** to start (or use its Later / Lobby
buttons), tap the video for the controls bar (pause, a music-volume slider, queue, menu), tap the score
to continue.
The phone's Back gesture does what BACK does on the remote.

## Guests' phones

The TV runs a tiny web page for the party. Guests scan the QR code, type their name once, search, and tap **+**.
Their phones also show who's singing and the queue, updated live.

- Phones must be on the **same Wi-Fi** as the Shield. Guest networks often block devices from seeing each
  other; if the page won't open, use the main network.
- Security: the page only answers devices on your local network, and only with the secret key that's inside
  the QR code. Wrong keys are limited to a few tries a minute, and the key changes after 12 hours (only
  between parties, so phones that already joined keep working). Phones can only add songs that came out
  of the karaoke search. There are limits on request size and time, on how many songs or searches one
  phone can make per minute, and at most 3 queued songs per singer.

## Searching: with or without a YouTube API key

**Without a key (default):** the app searches the same way YouTube's own website does, as open-source
apps like NewPipe do. No setup and no daily limit. It isn't an official API, so it's against YouTube's
terms of service, and if YouTube changes their website, search can stop working until the app is updated.
Video playback always uses YouTube's official embedded player either way.

**With a key (optional, more reliable):** uses the official YouTube Data API. It allows about 100 searches a
day (each search is saved on the Shield for 24 hours, so repeats are free), filters out videos that can't
be embedded, and falls back to key-less search automatically if the daily quota runs out.

To get a key:
1. Go to <https://console.cloud.google.com/>, create a project, and enable **YouTube Data API v3**
   (APIs & Services → Library).
2. **APIs & Services → Credentials → Create credentials → API key.**
3. Restrict it: **Application restrictions → Android apps**, package `com.karaoketv` and the SHA-1 shown by
   running `./gradlew signingReport` (use the line for the build you install). **API restrictions → YouTube Data API v3.**
4. Add it to `local.properties` in the project folder: `YOUTUBE_API_KEY=AIza…` and rebuild.

## Party country

Press the 🌍 button (it shows the chosen flag afterwards) and pick a country, or type a few letters
to find it. **International** is the original mix.

- **Quick picks**: Portugal, Brazil, the US, the UK, Ireland, Canada, Australia, Spain, Mexico,
  Argentina, Colombia, France, Italy, Germany, the Netherlands, the Philippines, Japan, South Korea
  and India have hand-picked local favourites, searched in the local language. Every other country
  gets picks built from its name and language (for example "Angola songs", "Polish songs").
- **Search ranking**: every search, typed or by voice, from the TV or a phone, is ranked by YouTube
  for that country and language. It favours local music; it doesn't hide anything else.
- The choice is remembered, and each country keeps its own saved results.

## Looks right on any TV

The whole interface scales with the screen: it's designed on the Android TV 960 x 540 canvas and stretched
to fill your TV exactly, whatever its resolution (720p, 1080p, 4K) or size. On a 75" TV everything is simply
bigger, and the proportions never change. Content stays inside the TV-safe area so nothing is cut off by
overscan, and text sizes are chosen to be read from across the room.

## Build it

### Option A: in the cloud, no Android Studio (easiest)

1. Create a free account on <https://github.com>, make a **new repository** (public), and upload the
   contents of this folder (drag and drop on the repository page, then **Commit**).
2. GitHub builds the app automatically (tab **Actions**, takes ~5 minutes). When it's done, the APK is
   under **Releases** on the right.
3. On the Shield, install the free **Downloader** app from the Play Store and enter:
   `https://github.com/YOUR-NAME/YOUR-REPO/releases/latest/download/KaraokeNight.apk`
   It downloads and installs the app. To update later, enter the same address again.

Optional repository secrets (Settings → Secrets and variables → Actions):
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` sign every build with your own key, so
updates install over the old version (otherwise each cloud build has a new key and you must uninstall
first, which resets the hall of fame). Don't add a YouTube API key to a public repository's builds: anyone
could download the APK and extract it.

### Option B: on your computer with Android Studio

1. Install **Android Studio** from <https://developer.android.com/studio> and open this folder (**File → Open**).
2. Build the small, optimised version. In Android Studio's **Terminal**:

   ```
   ./gradlew assembleRelease          (Windows: gradlew assembleRelease)
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk` (around 1–2 MB).
   *(Optional: to sign with your own key instead of the debug key, create `keystore.properties` with
   `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.)*

## Install it on the Shield

**Over Wi-Fi with adb (easiest for updates)**

1. Shield: **Settings → Device Preferences → About**, click **Build** 7 times.
2. **Settings → Device Preferences → Developer options → Network debugging**: on. Note the IP address.
3. Computer: `adb connect 192.168.1.50:5555` (the Shield's IP), accept the prompt on the TV, then
   `adb install -r app/build/outputs/apk/release/app-release.apk`.

**No commands**: send the APK with the **Send files to TV** app (phone → Shield) and open it with a file manager.

It appears in the Shield's app row as **Karaoke Night**.

## Made for the Shield's limited memory, with no leaks

- One screen instead of several; the YouTube player (the biggest memory user) only exists while the party
  is running and is released in the lobby.
- Small thumbnails decoded at half memory, a capped image cache, and caches dropped when Android asks for memory.
- Key-less search reads YouTube's reply as a stream instead of loading it all into memory.
- Lobby music is decoded a little at a time (never loaded whole into memory) and fed to the speaker
  as one continuous stream, so the loop point has no gap or click; effects are tiny.
- No heavy libraries: no RecyclerView, image or QR libraries. The QR code generator and the phone server
  are built in. Release builds are shrunk with R8.
- Pure Java/Kotlin with no native code, so it runs on the Shield's 32-bit (armeabi-v7a) and 64-bit setups alike.
- Leak-proofing: background work (searches, thumbnails, the phone server) only keeps weak links to the screen,
  every timer, animation, sound and player is released when the app closes, and the YouTube player is
  replaced with a fresh one every 6 songs so its web page can't keep growing during a long party.
- To check it yourself: debug builds (`./gradlew assembleDebug`) include **LeakCanary** and Android's
  StrictMode leak checks, which report any leaked screen, stream or receiver in Logcat. Release builds don't include them.

## Customise

| What | Where |
|---|---|
| Quick picks | `Countries.kt`: `INTERNATIONAL_PICKS`, and `HAND` for a country's own picks (phones get the same ones) |
| How generous the judge is | `Scores.kt`: `85 + 6 * rng.nextGaussian()`: raise 85 for higher scores, raise 6 for more spread |
| Judge comments | `Scores.kt` → `lines` |
| Lobby music | Replace `res/raw/lobby.ogg` with any OGG file (it loops from end to start without a gap); volume in `Audio.kt` → `LOBBY_VOLUME` |
| Autostart delay | `MainActivity.kt` → `AUTOSTART_MS` (30 s) |
| Surprise stage names | `MainActivity.kt` → `STAGE_NAMES` |
| Fresh player every N songs | `MainActivity.kt` → `RECYCLE_PLAYER_AFTER` |
| Max songs per singer | `Party.kt` → `MAX_PER_SINGER` |
| Covered seconds at the end of a song | `MainActivity.kt` → `END_COVER_S` |

## Troubleshooting

- **The app stopped**: open it again. It shows what went wrong instead of the party; take a photo of
  that screen to get it fixed, then press **Try again**.
- **No QR code / "Connect the TV to your home network"**: the Shield isn't connected to Wi-Fi or Ethernet.
- **🎙 says voice search isn't available**: type instead; the remote's own microphone button opens Google Assistant, not this app.
- **Phone says "Scan the QR code on the TV"**: scan again; the link includes the party key.
- **"YouTube search is unavailable"** without a key: YouTube changed something. Add an API key (above).
- **A video doesn't play**: some owners block playback outside YouTube, and no app can get around
  that. Search results are checked first, and videos whose owner blocks this are left out. If a video
  still refuses, the app plays a different karaoke version of the same song instead (it tries twice, then
  moves on), and remembers the refusal so that video no longer shows up. A channel whose videos refuse
  three times is hidden. To forget all of this, clear the app's storage in Android's app settings.
- **Ads**: YouTube may show ads before some videos, as in any app using YouTube's official player.
- **Video suggestions**: YouTube shows suggestion tiles over a paused video and in the last seconds of
  many videos, and no app can switch them off. Karaoke Night covers the video at those moments: while
  paused, and during the last 12 seconds (the music keeps playing). Change the seconds in
  `MainActivity.kt` → `END_COVER_S`.

---

Playback uses the open-source [android-youtube-player](https://github.com/PierfrancescoSoffritti/android-youtube-player)
library (YouTube's official embedded player in a WebView). Lobby music and sound effects were generated for this app.
