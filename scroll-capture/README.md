# Scroll Capture

An Android screen capture app for a Samsung phone. It replaces the screenshot
tool with one that can also capture long scrolling sections and pull out every
word, so a transcript or a long post can be pasted somewhere else.

## What it does

Press **Volume Up + Volume Down** together (or use the Quick Settings tile, or set
the side button's double press to open the app). It takes a screenshot **at once**.

A small thumbnail and a row of buttons then float over the live app. They have no
background, and touches anywhere else go straight through to the app, so you can keep
looking at it and scrolling it:

| Button | Result |
| --- | --- |
| Thumbnail (tap) | Opens the screenshot fitted whole on the screen; tap it again for full screen |
| Thumbnail (long-press) | Moves the buttons to the top or bottom |
| Scroll | One tall image of the whole scrolling section |
| Text | Every word of the whole page, in order, copyable |
| Both | The long image and its words |
| Share, Edit | The usual actions (Edit opens your normal image editor) |
| Close | Hides the buttons (they also hide after 15 seconds) |

Images are saved to `Pictures/Screenshots`, the same album as Samsung's own, so they appear
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
