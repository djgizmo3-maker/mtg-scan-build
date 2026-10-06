# MTG Scan & Build

An Android app that scans your Magic: The Gathering cards with the phone camera, keeps a full inventory of your collection, and builds playable decks for every legal format using only cards you own.

## Features

- **Camera scanning:** on-device text recognition (ML Kit) reads the card name inside an on-screen frame and fuzzy-matches it against every card name on Scryfall. Includes tap-to-focus, pinch-to-zoom, a flashlight toggle, optional auto-add with beep/vibration, and Undo.
- **Photo and search:** recognize a card from a gallery picture, or search by name.
- **Collection:** quantities, foils, set/printing choice, filters by color/type, search by name, type or rules text, CSV/list import and CSV export.
- **Deck builder:** generates decks from your collection for Standard, Pioneer, Modern, Legacy, Vintage, Pauper, Commander, Duel Commander, PDH, PreDH, Oathbreaker, Brawl, Standard Brawl, and the Arena formats. Every deck is checked for format legality and ownership, and shows its mana curve.

Market prices are intentionally not tracked.

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

Kotlin, Jetpack Compose, CameraX, ML Kit Text Recognition, Room, OkHttp, Coil. Card data and images come from the [Scryfall API](https://scryfall.com/docs/api).

## Disclaimer

Unofficial fan project. Magic: The Gathering is a trademark of Wizards of the Coast. Card data and images are provided by Scryfall. This app is not produced, endorsed, or supported by Wizards of the Coast or Scryfall.
