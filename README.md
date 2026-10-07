# MTG Scan & Build

An Android app that scans your Magic: The Gathering cards with the phone camera, keeps a full inventory of your collection, and builds playable decks for every legal format using only cards you own.

## Features

- **Camera scanning:** on-device text recognition (ML Kit) reads the card name inside an on-screen frame and fuzzy-matches it against every card name on Scryfall. Includes tap-to-focus, pinch-to-zoom, a flashlight toggle, optional auto-add, a scan sound with vibration, and Undo.
- **Scan sounds:** choose from Chime, Classic scanner beep, Coin, Mana sparkle, Bell ding, Laser, Deep thump, or your own audio file. Sounds use the media volume, so they work on vibrate.
- **Photo and search:** recognize a card from a gallery picture, or search by name.
- **Collection:** quantities, foils, set/printing choice, filters by color/type/set, search by name, type or rules text, CSV/list import and CSV export. You can sort by set (A–Z or newest first) with set headers, or by value.
- **TCGplayer prices:** TCGplayer market prices (normal and foil, via Scryfall) for each card, the whole collection and every deck, plus a link to the card on TCGplayer. Prices refresh daily (this can be turned off in Settings) or on demand.
- **Deck builder:** generates decks from your collection for Standard, Pioneer, Modern, Legacy, Vintage, Pauper, Commander, Duel Commander, PDH, PreDH, Oathbreaker, Brawl, Standard Brawl, and the Arena formats. Every deck is checked for format legality and ownership, and shows its mana curve. Each card shows which sets your copies come from.
- **Moxfield comparison:** search popular Moxfield decks by format (or paste a deck link). Each deck shows what percentage of it you already own, which cards are missing, and what the missing cards cost. You can save a deck to your list or copy the missing cards for TCGplayer Mass Entry.
- **Settings tab:** scan sound, volume, auto-add, vibration, keep-screen-on, start with the light on, and recognition strictness. Also theme (dark/light/follow phone), accent color (including Android 12+ wallpaper colors), card pictures on or off, default deck format and basic-lands default. You can also update the card-name list and clear the image cache.

## Install

Download the APK from the [Releases](../../releases) page, copy it to your phone, allow "Install unknown apps", and open it. The first launch needs internet to download the card-name list (one time). Requires Android 8.0 (API 26) or newer.

## Build from source

Requirements: JDK 17 and the Android SDK (API 36).

```
gradlew.bat assembleRelease      # Windows
./gradlew assembleRelease        # macOS / Linux
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Unit tests (`gradlew testDebugUnitTest`) call the live Scryfall API.

## Tech

Kotlin, Jetpack Compose, CameraX, ML Kit Text Recognition, Room, OkHttp, Coil. Card data, images and TCGplayer market prices come from the [Scryfall API](https://scryfall.com/docs/api). Deck lists come from Moxfield's public, but unofficial, API, which is rate-limited, so comparing a page of decks takes about 30 seconds.

## Disclaimer

Unofficial fan project. Magic: The Gathering is a trademark of Wizards of the Coast. Card data and images are provided by Scryfall. Prices are TCGplayer market prices and are for reference only. This app is not produced, endorsed, or supported by Wizards of the Coast, Scryfall, Moxfield or TCGplayer.
