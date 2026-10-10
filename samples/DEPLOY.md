# Record Room — putting it online

**Easiest path: [SETUP.md](SETUP.md).** Two steps in a browser, then GitHub
deploys it for you on every change. No terminal, nothing to install.

The rest of this file is the manual route, for deploying from your own
computer.

One self-contained HTML file. It pulls the current season from Sleeper in the
browser on every load, so once it is online it keeps itself up to date with no
server, no database and no scheduled job.

Record Room gets its **own site**, separate from anything else in the
Firebase account. `firebase.json` names that site explicitly, so a deploy from
this repo can only ever touch the record book.

    "hosting": { "site": "record-room", "public": "public" }

## First time

    npm install -g firebase-tools        # once per computer
    firebase login                       # once, opens a browser
    firebase projects:create <project-id>      # its own project, once
    firebase use <project-id>                  # writes it to .firebaserc
    npm run site:create                  # claims record-room.web.app
    npm run deploy

The site is then at:

    https://record-room.web.app

Anyone with the link can open it. No account, no sign-in.

### Picking a different name

Change `site` in `firebase.json` and the name in the `site:create` script in
`package.json` to match, then run both commands. The name has to be unused
across all of Firebase. These were unclaimed as of this writing:

    record-room   the-record-room   trophy-case
    the-ledger    etched

Record Room has its own Firebase project, named in `.firebaserc`
(currently `record-room-32ef8`). It shares nothing with any other project.
The first-time steps above create and select it.

## Security policy

`npm run build:site` adds a Content-Security-Policy to the page: only the
page's own script may run, and it may only talk to `api.sleeper.app`. The
script's hash is recomputed on every build. If you add a second `<script>`,
an external script, or an inline `onclick=` handler, the build stops and says
so, because the browser would block it. Other policy directives live in the
headers block of `firebase.json`.

## Publishing a change

    npm run deploy

`index.html` is served with `Cache-Control: no-cache`, so the next refresh
picks up the new build rather than a stale copy.

## Pointing it at a different league

Everything tying the build to one league is at the top of the script in
`samples/record-room-standalone.html` (and its wrapper-less twin,
`samples/record-room.html`, if you keep that copy):

    const APP_NAME='Record Room';
    const LEAGUE_ID='1325921258503667712';

Change `LEAGUE_ID`, regenerate the baked history with the scripts in
`samples/tools/`, and rebuild (`npm run build:site`). Nothing else in the file assumes a league, so
letting people enter their own ID later means reading `LEAGUE_ID` from the
page instead of that constant.

The league's display name is not hardcoded: it comes from Sleeper on each
load, so renaming the league in Sleeper renames it here too.

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

Firebase console, Hosting, pick the **record-room** site, Add custom
domain. Firebase issues the certificate. Point the domain's A records at the
addresses it shows.
