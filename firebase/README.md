# Friends mailbox backend

The mailbox has no server code. Firestore stores sealed messages, and `firestore.rules` decides who
may write where. This folder holds those rules, the emulator tests that pin them down, and the
one-off setup the app needs before the mailbox works.

## One-off setup

1. **Firebase project.** In the Firebase console, add Firebase to the Google Cloud project that owns
   `google_web_client_id` in `app/src/main/res/values/strings.xml`. Firebase Authentication only
   accepts Google ID tokens issued to its own project's OAuth clients.
2. **Authentication → Sign-in method → Google:** enable it.
3. **Firestore Database:** create the default database in Native mode. The free Spark plan is enough.
4. **App config.** Project settings → General: copy the Web API key and the project ID into
   `firebase_api_key` and `firebase_project_id` in `strings.xml`. Both are public identifiers, like
   the client ID. Leave them blank and the app says the mailbox is not available in that build.
5. **Restrict the API key** in the Google Cloud console, under APIs & Services → Credentials:
   - API restrictions: Identity Toolkit API, Token Service API, Cloud Firestore API.
   - Application restrictions: Android apps, package `com.varun.upitracker`, with the SHA-1 of both
     the debug and the release signing certificates. The app sends `X-Android-Package` and
     `X-Android-Cert` on every request, which is what this restriction checks.
6. **Deploy the rules** (below). Until you do, Firestore refuses every request.

## Deploying the rules and the site

```bash
cd firebase
npm install
npx firebase login
npx firebase use --add
npm run deploy
```

`npm run deploy` publishes both the rules and `public/`, which is served at
`https://<project>.web.app`. `npm run deploy:rules` publishes only the rules.

## The invite link

An invite is shared as `https://dhanmoney.web.app/link#<code>`, because a chat app makes a link
tappable and a bare code only copyable. The code sits in the fragment, so it never reaches Hosting:
neither this site nor the link preview a chat app fetches ever sees it.

For a tap to open the app rather than the page, Android has to be told this app speaks for the host:

1. Get the **SHA-256** of every signing certificate:

   ```bash
   ./gradlew :app:signingReport
   ```

   Take the SHA-256 of the `debug` and `release` variants. If you publish through Play, also take
   the app signing key's SHA-256 from Play Console &rarr; Test and release &rarr; App integrity.
2. Put them in `public/.well-known/assetlinks.json`, replacing the two placeholders. Any number of
   fingerprints may be listed.
3. Deploy, then check the file is served as JSON:

   ```bash
   curl https://dhanmoney.web.app/.well-known/assetlinks.json
   ```
4. Reinstall the app. Android verifies the host at install time; `adb shell pm get-app-links
   com.varun.upitracker` says whether it succeeded.

The host is named twice and both must agree: the `https` intent filter in `AndroidManifest.xml`, and
`mailbox_link_host` in `strings.xml`, which is what builds the link that gets shared. Pointing them
somewhere else (a GitHub Pages site, say) works just as well, as long as that host serves the same
`/.well-known/assetlinks.json` over HTTPS.

Two things it cannot do. A chat app that opens links in its own in-app browser may show the page
instead of the app, which is why the page carries an **Open in DhanMoney** button and the code to
copy. And before the app is installed there is nothing to open, so the page is what a new user sees.

## Testing the rules

The Firestore emulator needs a recent Java on the `PATH` (current `firebase-tools` asks for 21).

```bash
cd firebase
npm install
npm test
```

## Limits worth knowing

- Spark allows 50,000 reads, 20,000 writes and 20,000 deletes a day, shared by every user. The app
  collects the inbox only at launch, when the dashboard comes back (at most every 10 minutes) and
  when the inbox screen opens. An empty collection still costs one read, and each rule check that
  looks up a contact or an invite costs one more.
- TTL policies need billing, so nothing expires on the server by itself. A recipient deletes each
  message once it is stored on their phone, and deleting an account takes back what nobody
  collected.
- Cloud Functions need the Blaze plan, so there is no push. A message arrives the next time its
  recipient opens the app.

## Before publishing on Google Play

- **Account deletion.** Turning the mailbox on creates an account, so Play requires a way to delete
  it in the app (Settings → Friends mailbox → Delete mailbox account) and on a web page. That page is
  `public/account-deletion.html`, served at `https://dhanmoney.web.app/account-deletion` once
  deployed. Fill in its contact address (and the same one in `docs/account-deletion.md`, which says
  the same thing for anyone reading the repository), then give the URL in the Data safety form.
- **Data safety form.** Email address and user IDs are collected for account management. Transaction
  details only leave the phone end-to-end encrypted; check Play's current guidance on how to declare
  end-to-end encrypted data.
