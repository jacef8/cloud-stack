# Setting up Record Room — browser only

Record Room gets its **own Firebase project**. Nothing it does touches any
other project, and they share no settings, quota or billing.

Two steps in a browser. No terminal, nothing to install.

## 1. Make the project and get a key

1. Open <https://console.firebase.google.com/> and sign in.
2. Click **Create a project** (not an existing one).
3. Name it `Record Room`. Firebase suggests a project ID underneath, something
   like `record-room-32ef8` — whatever it lands on is fine, you don't have to
   write it down.
4. Google Analytics is not needed. Turn it off and click **Create project**.
5. When it finishes, click the **gear** next to Project Overview, then
   **Project settings**.
6. Open the **Service accounts** tab.
7. Click **Generate new private key**, then **Generate key** to confirm.
8. A `.json` file downloads. Open it in Notepad or TextEdit and copy
   **everything**, from the first `{` to the last `}`.

That file is a password for the project. Don't email it, don't paste it into a
chat, and delete the download once step 2 is done.

## 2. Paste it into GitHub

1. Open <https://github.com/jacef8/cloud-stack/settings/secrets/actions>
2. Click **New repository secret**.
3. Name: `FIREBASE_SERVICE_ACCOUNT`
   Secret: paste the whole contents of that `.json` file.
4. Click **Add secret**.

The deploy reads the project ID out of that key, so whichever ID Firebase gave
you is the one it uses. There is nothing to keep in sync.

## 3. Run it

1. Open <https://github.com/jacef8/cloud-stack/actions>
2. Click **Deploy Record Room** on the left.
3. Click **Run workflow**, then the green **Run workflow** button.

About a minute. When the check turns green the site is live at:

    https://record-room.web.app

The last line of the log prints the URL.

## From then on

Any change to the site that lands on `master` deploys on its own. You can also
hit **Run workflow** any time.

## If it fails

- **"The FIREBASE_SERVICE_ACCOUNT secret is not set"** — step 2 didn't save.
- **"not valid JSON"** — only part of the file got pasted. Copy all of it,
  braces included.
- **"Hosting site record-room already exists"** in another project — someone
  claimed that name. Change `site` in `firebase.json` to something else, for
  example `the-record-room`, and run it again.
- **Billing or API errors on a brand new project** — open the Hosting page in
  the console once and click **Get started**. That switches Hosting on.

## Where things live

| What | Where |
|---|---|
| The site, source of truth | `samples/record-room-standalone.html` |
| Same page without the HTML wrapper (for pasting as an artifact; keep in sync) | `samples/record-room.html` |
| Built file that gets served | `public/index.html` (generated, not committed) |
| Which Firebase site | `site` in `firebase.json` |
| Which Firebase project | the key in the GitHub secret |
| The league it reads | `LEAGUE_ID` near the top of the script |
