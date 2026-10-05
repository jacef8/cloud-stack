# Scroll Capture

An Android screen capture app for a Samsung phone. It replaces the screenshot
tool with one that can also capture long scrolling sections and pull out every
word, so a transcript or a long post can be pasted somewhere else.

## What it does

Press **Volume Up + Volume Down** together (or use the Quick Settings tile, or set
the side button's double press to open the app). It takes a screenshot **at once**
and opens it fitted whole on the screen, with no scrolling. Tap the picture to hide
the bars for a full-screen view.

From the toolbar under the picture:

| Button | Result |
| --- | --- |
| Share, Edit, Copy, Delete | The usual screenshot actions (Edit opens your normal image editor) |
| Scroll | Goes back to that app and captures one tall image of the whole scrolling section |
| Text | Goes back and reads every word, in order, copyable |
| Both | The long image and its words |

Images are saved to `Pictures/Screenshots` next to Samsung's own; text goes to
`Documents/ScrollCapture`. Very long captures scroll in the viewer.

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
