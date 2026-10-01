# Setting up Record Room — browser only

Two things to do, both in a web browser. No terminal, nothing to install.
After this, the site redeploys itself whenever the page changes.

## 1. Get a key from Firebase

1. Open <https://console.firebase.google.com/> and sign in.
2. Pick the project **allstars-live**.
   (Prefer a brand new project? Click **Add project** first, name it anything,
   then use that one here and change `default` in `.firebaserc` to its ID.)
3. Click the **gear** next to Project Overview, then **Project settings**.
4. Open the **Service accounts** tab.
5. Click **Generate new private key**, then **Generate key** to confirm.
6. A `.json` file downloads. Open it in Notepad or TextEdit and copy
   **everything**, from the first `{` to the last `}`.

That file is a password for the project. Don't email it, don't paste it into a
chat, and delete the download once step 2 is done.

## 2. Paste it into GitHub

1. Open <https://github.com/jacef8/cloud-stack/settings/secrets/actions>
2. Click **New repository secret**.
3. Name: `FIREBASE_SERVICE_ACCOUNT`
   Secret: paste the whole contents of that `.json` file.
4. Click **Add secret**.

## 3. Run it

1. Open <https://github.com/jacef8/cloud-stack/actions>
2. Click **Deploy Record Room** on the left.
3. Click **Run workflow**, then the green **Run workflow** button.

It takes about a minute. When the check turns green, the site is live at:

    https://record-room.web.app

The last line of the log prints the URL.

## From then on

Any change to the site that lands on `master` deploys on its own. You can also
hit **Run workflow** any time to push the current version.

## If it fails

- **"The FIREBASE_SERVICE_ACCOUNT secret is not set"** — step 2 didn't save.
- **"not valid JSON"** — only part of the file got pasted. Copy all of it,
  braces included.
- **"HTTP Error: 403"** — the key is from a different project than the one in
  `.firebaserc`. Generate the key from the project named there.
- **The site name is taken** — change `site` in `firebase.json` to something
  else and run it again.

## Where things live

| What | Where |
|---|---|
| The site, source of truth | `samples/record-room.html` |
| Built file that gets served | `public/index.html` |
| Which Firebase site | `site` in `firebase.json` |
| Which Firebase project | `projects.default` in `.firebaserc` |
| The league it reads | `LEAGUE_ID` near the top of the script |
