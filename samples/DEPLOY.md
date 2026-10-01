# Putting the record book online

The site is one self-contained HTML file. It pulls the current season from
Sleeper in the browser on every load, so once it is online it keeps itself
up to date with no server, no database and no scheduled job.

## What is already set up

- `firebase.json` serves the `public/` folder, project `allstars-live`
- `public/index.html` is the built site
- `npm run build:site` copies the latest build into `public/`

## Deploy

    npm install -g firebase-tools     # once
    firebase login                    # once, opens a browser
    npm run deploy

That prints the live URL, which will be:

    https://allstars-live.web.app

Anyone with the link can open it. No account, no sign-in.

## Publishing a change

    npm run deploy

The HTML is served with `Cache-Control: no-cache`, so the next refresh picks
up the new build rather than a stale copy.

## How the data stays current

Finished seasons never change, so 2023 through 2025 are baked into the file.
Only the current season is fetched: about fifteen requests to
`api.sleeper.app`, which sends `Access-Control-Allow-Origin: *` and needs no
key. The page re-checks every ten minutes while it is open, and the status
chip in the header can be clicked to refresh on demand. The last good result
is kept in the browser, so the page still shows the book if Sleeper is down,
labelled with the time it was saved.

At the end of this season, regenerate the baked history so the finished year
becomes permanent:

    python3 samples/tools/build.py
    python3 samples/tools/compute.py
    python3 samples/tools/history.py

## A custom domain

In the Firebase console, Hosting, Add custom domain. Firebase issues the
certificate. Point the domain's A records at the addresses it shows.
