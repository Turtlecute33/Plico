# Plico

A private, minimal email app for Android, made for **Riseup** and **Gmail**.

- **Fast push without Google.** New mail arrives through IMAP IDLE in seconds. Plico uses no Firebase and no
  server of its own, and it keeps battery use low with one connection per account and adaptive keep-alives.
- **Private by default.** Remote images are blocked, tracking pixels are removed, and links are cleaned and
  shown before they open. Outgoing mail carries no device name, no time zone and no app name.
- **No tracking.** The app has no analytics, no crash reporting and no ads. It connects only to your mail servers,
  to Google sign-in if you choose it, and to remote content after you tap "Show".
- **Small.** Plico has its own IMAP and SMTP code and only a few libraries.

Status: early development. Android 15 or later.

## Features

- Riseup and Gmail accounts, a unified inbox, folders and Gmail labels, swipe actions, local and server search.
- Gmail sign-in with an app password, with Google sign-in in the browser, or with the Google account on the phone.
  The last two need your own Google Cloud OAuth client (see below).
- Gmail: archive, labels, Important, categories for notifications, Spam and Trash.
- Riseup: the server folders, aliases as identities, quota, and links to the account.riseup.net settings.
- Offline: recent mail is kept in full and older mail opens on demand. Optionally, all mail is kept offline.
- Attachments: image location data is removed before sending, and risky file types get a warning before they open.
- App lock with fingerprint or screen lock. Notifications can hide the sender and subject.

## Build

Requirements: JDK 21 and the Android SDK (platform 37).

```sh
./gradlew :mail:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`.

## Gmail with Google sign-in (optional)

An **app password** works without any setup. It needs 2-Step Verification on the Google account:
<https://myaccount.google.com/apppasswords>.

Google sign-in needs an OAuth client that you own, because Google ties the client to the app's package name and
signing key:

1. In the Google Cloud console, create a project and configure the OAuth consent screen: "External", publishing
   status "In production", and a support email that is not personal.
2. Create an OAuth client of type **Android** with the package name `app.plico` and the SHA-1 of the key that signs
   your build. Debug builds use the package `app.plico.debug` and need their own client.
3. In Plico, open Settings → Gmail sign-in and paste the client ID. For debug builds you can also set
   `plico.googleClientId=<id>` in `local.properties`.

Google shows an "unverified app" screen at sign-in. This is normal for a personal client. Release builds never
contain a client ID. If you publish an APK, sign it with a different key than your personal build.

## Privacy

Plico stores your mail on the phone in Android's encrypted app storage. Passwords and tokens are encrypted with
a key in the Android Keystore. Nothing is backed up or transferred to other devices. Gmail adds the sender's public
IP address to every message sent through its servers. Riseup removes it.

## Licence

Not chosen yet.
