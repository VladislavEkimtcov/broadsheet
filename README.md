# Broadsheet: Apple News on Android TV and Fire TV

**A news reader for the TV, built for the remote.** Broadsheet shows your Apple News (Today's
Top Stories, For You, Trending, your topics and channels, Saved Stories) on an Android TV or
Fire TV, and reads whole articles in a calm, newspaper-style reader. No ads, no touch, no
account on the TV.

Apple News has no Android app and no reader API, so Broadsheet gets the news from
[**Apple News Relay**](https://github.com/VladislavEkimtcov/apple-news-relay), a small server
that runs on your Mac and turns the News app into a local JSON API. The TV finds the relay on
your network by itself.

- **Front page:** a rail of sections on the left; a hero story, two briefs and rows of picture
  cards. Moving along the rail skims sections; cached pages open instantly.
- **Reader:** a single column of serif type (Newsreader, SIL OFL), pictures with captions, quotes
  and subheads. The headline, summary and picture show at once; the body follows, fetched from
  Apple News on the Mac if it wasn't there yet.
- **D-pad only:** Down/OK reads on (OK at the end opens the next story), Up goes back, Left/Right
  the previous/next story, Fast-forward/Rewind a page, Menu the text size, Back the front page.
- **No ads:** the relay removes ad slots, sponsored stories and publisher promotions.
- **Apple News+:** whole stories when the Mac's account has Apple News+; otherwise the opening,
  clearly marked.
- **Light on a small TV box:** one view per card, pictures decoded at the size shown and dropped
  when far off screen, nothing animating at rest.

## Setup

1. On the Mac, run Apple News Relay for your network:
   ```sh
   git clone https://github.com/VladislavEkimtcov/apple-news-relay.git && cd apple-news-relay
   python3 -m apple_news_relay serve --host 0.0.0.0 --allow 127.0.0.1,192.168.1.0/24
   ```
2. Install Broadsheet on the TV (see [Build](#build)), open **News**. It finds the relay with
   Bonjour (`_newsrelay._tcp`).

If your network blocks Bonjour, point it at the relay by hand: put a `relay.conf` in the app's
files folder

```
url=http://192.168.1.20:8787
# token=...   if the relay was started with --token
```

```sh
adb push relay.conf /sdcard/Android/data/io.github.vladislavekimtcov.broadsheet/files/relay.conf
```

## Build

No Gradle, no libraries: the bare Android SDK and JDK 17.

```sh
./build.sh                          # build/broadsheet.apk, signed with release.keystore (made on first run)
adb install -r build/broadsheet.apk
```

`build.sh` needs `platforms;android-25` and any build-tools (34 tested). Keep `release.keystore`
(it's git-ignored): updates must be signed with the same key. Banner: `python3 tools/art.py`
(Pillow). Fonts: `python3 tools/fonts.py` (fontTools).

## How it talks to the relay

| | |
|---|---|
| front page | `GET /v1/sections?per_section=30`, `GET /v1/articles?limit=45` (Latest), `GET /v1/saved` |
| a story | `GET /v1/articles/{id}?wait=14`: the relay answers as soon as News has the body |
| still fetching | `GET /v1/jobs/{id}?wait=30`, then the article again |
| freshness | `POST /v1/today/refresh?pages=6` at most every 25 minutes, then a quiet reload |

The last front page is kept on disk, so the app opens instantly and still works for a while if
the Mac sleeps.

## Requirements

Android 7.1 (API 25) or later with a D-pad remote: Fire TV Stick and Cube, Fire TV smart TVs,
Google TV, Android TV. Built and tuned on a Fire OS 8 (Android 11) TV with a MediaTek MT5896.

## License

MIT. Fonts: Newsreader by Production Type, SIL Open Font License 1.1 (`assets/fonts/OFL.txt`).
Not affiliated with Apple or Amazon. Stories belong to their publishers.
