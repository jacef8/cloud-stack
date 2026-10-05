# Scroll Capture

An Android screen capture app for a Samsung phone. It replaces the screenshot
tool with one that can also capture long scrolling sections and pull out every
word, so a transcript or a long post can be pasted somewhere else.

## What it does

Press **Volume Up + Volume Down** together (or use the Quick Settings tile, or set
the side button's double press to open the app). It takes a screenshot **at once**.

The instant the picture is taken there is a **camera shutter click and a short, sharp buzz**.

Then, laid out like Samsung's, a thumbnail (bottom-left) and a wide dark bar of icons (bottom-centre)
float over the live app. They fade in, then **fade away by themselves after about five seconds**;
holding a finger on them keeps them a little longer, and swiping the thumbnail aside sends them away
sooner. Touches anywhere else go straight to the app.

| Control | Result |
| --- | --- |
| Thumbnail (tap) | Opens the screenshot fitted whole on the screen; tap it again for full screen. The full-screen viewer also has Both (image + text), Copy and Delete |
| Edit | Opens your normal image editor |
| Text | Every word of the whole page, in order, copyable |
| Share | The usual share sheet |
| White circle (Scroll) | One tall image of the whole scrolling section |

The moment you tap Scroll, a **"Getting ready… · tap to stop"** pill appears near the top, then "Capturing… screen N · tap to stop" while it runs
(the phone buzzes when it starts, and clicks and buzzes when it is done). Scrolling is done like a finger drag of under half the area that pauses before lifting (so the list does not coast on), so each picture overlaps the last and they can be lined up. A busy page with a playing video still lines up as long as one position clearly matches. A scroll that works **replaces** the first screenshot, so there is one continuous image; one that does not keeps the original and adds no duplicate. Notes about a capture (stopped early, nothing to scroll) show beside the thumbnail, where nothing covers them. It stops by itself if the screen changes in a way it
cannot follow, or if you leave the app, and tells you why. Everything captured up to that point is kept.

Images are saved to `DCIM/Screenshots`, the folder Samsung phones use for their own screenshots, so they appear
in Gallery with the other screenshots. Text goes to `Documents/ScrollCapture`.

Some screens cannot be captured at all: Android blocks screenshots on Settings pages and in
some banking and password apps. The app says so instead of failing silently.

## How it works

- It is an **accessibility service**, the only way Android lets an app take
  screenshots of other apps, scroll them, and read their text.
- **Long capture:** it scrolls the screen one page at a time, takes a screenshot each
  time, and lines the pages up by matching rows of pixels. A fixed header and footer
  are kept once. Rows are streamed to disk, so very long captures do not run out of memory.
- **Text:** read from the app's accessibility tree (the real words, no guessing). If an
  app hides its text, on-device OCR reads the picture instead.
- **Private:** the app has no network permission. Nothing leaves the phone.

## Install

Each push builds the APK with GitHub Actions and publishes it at
`releases/tag/scroll-capture-latest`. Open that on the phone and tap `ScrollCapture.apk`.
Every build is signed with the same key, so new builds install as updates and keep
the accessibility permission.

First run: turn on Scroll Capture under Settings, Accessibility, Installed apps. If the
switch is greyed out, open App info, tap the three dots, choose **Allow restricted
settings**, and try again.

## Build

```
cd scroll-capture
./gradlew testDebugUnitTest assembleRelease
```

The stitching, PNG writing and text merging are plain Kotlin under
`app/src/main/java/com/jacef8/scrollcapture/core/`, with unit tests that rebuild a
long page from scrolled frames and check it matches the original.
