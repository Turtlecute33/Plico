# Plico

A private, minimal email app for Android. It works with IMAP mail accounts.

- **Fast push without Google.** New mail arrives through IMAP IDLE in seconds. Plico uses no Firebase and no
  server of its own, and it keeps battery use low with one connection per account and adaptive keep-alives.
- **Private by default.** Remote images are blocked, tracking pixels are removed, and links are cleaned and
  shown before they open. Outgoing mail carries no device name, no time zone and no app name.
- **No tracking.** The app has no analytics, no crash reporting and no ads. It connects only to:
  - your mail servers;
  - Google or Microsoft sign-in, if you choose it;
  - the autoconfig address of your mail domain (HTTPS) and DNS, only when you add an account;
  - the logo source that you select, only when you turn on sender logos: DuckDuckGo, Google, or the website of
    each sender (and the hosts it redirects to);
  - remote content, after you tap "Show".
- **Small.** Plico has its own IMAP and SMTP code and only a few libraries.

Status: early development. Android 15 or later.

## Features

- Any IMAP account with SSL/TLS or STARTTLS; setup finds the servers. You type only your address. Plico never
  connects without encryption.
- A unified inbox, folders and labels, swipe actions, local and server search.
- Servers without IDLE: Plico checks for new mail at an interval.
- Clear message list: unread mail gets bold text, a dot and a colour for each sender. Sender logos are
  optional and off by default.
- Gmail accounts: sign-in with an app password, with Google sign-in in the browser, or with the Google account on
  the phone. The last two need your own Google Cloud OAuth client (see below). Archive, labels, Important,
  categories for notifications, Spam and Trash.
- Outlook.com and Microsoft 365 accounts: sign-in in the browser with your own Microsoft client (see below).
- Riseup accounts: the server folders, aliases as identities, quota, and links to the account.riseup.net
  settings.
- Offline: recent mail is kept in full and older mail opens on demand. Optionally, all mail is kept offline.
- Attachments: image location data is removed before sending, and risky file types get a warning before they open.
- App lock with fingerprint or screen lock. Notifications can hide the sender and subject.

## Build

Requirements: JDK 21 and the Android SDK (platform 37).

```sh
./gradlew :mail:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`.

Release builds are signed with a key whose passwords stay in the macOS Keychain. To make a key once, run
`scripts/make-release-key.sh`, then build with `./gradlew :app:assembleRelease`. Without `keystore.properties`
the release APK is unsigned. Keep the keystore and its Keychain items together: updates need the same key.

## Gmail with Google sign-in (optional)

An **app password** works without any setup. It needs 2-Step Verification on the Google account:
<https://myaccount.google.com/apppasswords>.

Google sign-in needs an OAuth client that you own, because Google ties the client to the app's package name and
signing key:

1. In the Google Cloud console, create a project and configure the OAuth consent screen: "External", publishing
   status "In production", and a support email that is not personal.
2. Create an OAuth client of type **Android** with the package name `app.plico` and the SHA-1 of the key that signs
   your build. Debug builds use the package `app.plico.debug` and need their own client.
3. In Plico, open Settings → Sign-in clients → Google client ID and paste the client ID. You can also paste it on
   the setup page "Use your own Google OAuth client". For debug builds you can also set
   `plico.googleClientId=<id>` in `local.properties`.

Google shows an "unverified app" screen at sign-in. This is normal for a personal client. Release builds never
contain a client ID. If you publish an APK, sign it with a different key than your personal build.

## Outlook.com and Microsoft 365 (optional)

Microsoft lets an app open a mailbox only through a registered client. Plico has no client of its own, so you
register one. You need a directory: a work account or a free Azure account.

1. Open the Microsoft Entra admin center: <https://entra.microsoft.com/>.
2. Go to App registrations → New registration. For the account types, select "Any Entra ID tenant and personal
   Microsoft accounts".
3. Go to Authentication → Add a platform → Mobile and desktop applications. Add the redirect URI
   `app.plico://oauth2redirect`. Debug builds use `app.plico.debug://oauth2redirect`.
4. Copy the Application (client) ID. In Plico, paste it on the setup page "Use your own Microsoft client" or in
   Settings → Sign-in clients → Microsoft client ID. For debug builds you can also set
   `plico.microsoftClientId=<id>` in `local.properties`.

Outlook.com: first turn on IMAP. In Outlook.com, go to Settings → Mail → Forwarding and IMAP, and turn on IMAP
for devices and apps. A work or school account can need the approval of its administrator. If the administrator
turned off SMTP AUTH for the mailbox, Plico can receive mail but cannot send it.

Microsoft keeps the access of Plico after you remove the account. To stop it, go to
<https://account.live.com/consent/Manage> (personal account) or <https://myapps.microsoft.com> (work or school
account).

## Other accounts

Type your address and select Next. Plico looks for the servers in its built-in list, then in the autoconfig file
of your mail domain (HTTPS), then in DNS. It sends only the domain, never your address. If it finds nothing, or
if you select "Enter the server settings yourself", enter the servers from the help pages of your provider. Some
providers need an app password or IMAP access turned on; setup tells you when it knows this. Proton Mail, Tuta
and HEY have no IMAP access that Plico can use.

## Privacy

Plico stores your mail on the phone in Android's encrypted app storage. Passwords and tokens are encrypted with
a key in the Android Keystore. Nothing is backed up or transferred to other devices.

Sender logos are off by default. When you turn them on, Plico sends only the domain of each sender (never the
address) to the logo source that you select. With the source "Website of the sender", Plico gets a logo only for
a sender that sent mail to you before. Plico never gets a logo for your own mail or for mail in Spam, and opening
a message never gets a logo.

Gmail adds the sender's public IP address to every message sent through its servers. Riseup removes it.

## Licence

Plico is free software under the GNU General Public License v3.0. See [LICENSE](LICENSE).
