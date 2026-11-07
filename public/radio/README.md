# Local Radio

A phone web app that finds the radio stations around you and streams them.

Live at **https://record-room.web.app/radio/** once deployed (it ships with the rest of `public/`).

## Using it on your phone
1. Open the link and allow location.
2. Add it to your home screen so it opens like an app:
   - **iPhone (Safari):** Share → *Add to Home Screen*
   - **Android (Chrome):** ⋮ menu → *Install app*
3. Tap a station, use ⏮ / ⏭ to seek up and down the FM dial, or drag on the dial.
   Tap ☆ to save favorites. Lock-screen and headphone controls work while it plays.

## How it works
- Phones don't let web apps (or most apps) use an FM tuner chip, so this streams each
  station's **internet feed** instead. It uses mobile data or Wi-Fi, not an antenna.
- Stations come from the free, open [Radio Browser](https://www.radio-browser.info)
  directory, searched by your GPS position and the radius you pick.
- The FM/AM frequency is read from the station's listing (e.g. "WXRT 93.1 FM").
  Stations that don't list one show under *Other local streams*.
- A few stations only stream over plain `http://`; phones block that inside a secure
  page, so the app tries the `https://` version and tells you if it can't play.

Everything is in `index.html`; there's no build step.
