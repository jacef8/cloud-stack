# Putting the record book online

One self-contained HTML file. It pulls the current season from Sleeper in the
browser on every load, so once it is online it keeps itself up to date with no
server, no database and no scheduled job.

The record book gets its **own site**, separate from anything else in the
Firebase account. `firebase.json` names that site explicitly, so a deploy from
this repo can only ever touch the record book.

    "hosting": { "site": "tribes-of-gridiron", "public": "public" }

## First time

    npm install -g firebase-tools     # once per computer
    firebase login                    # once, opens a browser
    npm run site:create               # claims tribes-of-gridiron.web.app
    npm run deploy

The site is then at:

    https://tribes-of-gridiron.web.app

Anyone with the link can open it. No account, no sign-in.

### Picking a different name

Change `site` in `firebase.json` and the name in the `site:create` script in
`package.json` to match, then run both commands. The name has to be unused
across all of Firebase. These were unclaimed as of this writing:

    tribes-of-gridiron   12-tribes-gridiron   tribes-gridiron
    gridiron-record-book tribes-record-book

### Keeping it out of the existing project entirely

The steps above add a second site inside the Firebase project the repo already
points at (`allstars-live` in `.firebaserc`). That is only a container: the two
sites have separate URLs and separate contents, and deploying one never touches
the other.

For a completely separate project instead:

    firebase projects:create tribes-of-gridiron
    firebase use tribes-of-gridiron
    npm run site:create
    npm run deploy

## Publishing a change

    npm run deploy

`index.html` is served with `Cache-Control: no-cache`, so the next refresh
picks up the new build rather than a stale copy.

## How the data stays current

Finished seasons never change, so 2023 through 2025 are baked into the file.
Only the current season is fetched: about fifteen requests to
`api.sleeper.app`, which sends `Access-Control-Allow-Origin: *` and needs no
key. The page re-checks every ten minutes while open, and the status chip in
the header can be clicked to refresh on demand. The last good result is kept in
the browser, so the page still shows the book if Sleeper is down, labelled with
the time it was saved.

At the end of this season, bake the finished year into permanent history:

    python3 samples/tools/build.py
    python3 samples/tools/compute.py
    python3 samples/tools/history.py
    npm run deploy

## A custom domain

Firebase console, Hosting, pick the **tribes-of-gridiron** site, Add custom
domain. Firebase issues the certificate. Point the domain's A records at the
addresses it shows.
