# Live tests on your phone

These tests check what only real servers can show (DESIGN.md §16). Use the **debug APK**
(`app.plico.debug`). It installs next to a later release build and keeps its own data.

Before you start:

1. Make sure that the phone runs Android 15 or later.
2. Install the APK. Open Plico and add your accounts. You type the passwords; nobody else sees them.
3. Allow notifications and the battery exemption when Plico asks.
4. Write down the time of each step. "Delay" means the time from sending a mail to its notification.

## Riseup

| # | Do this | Expected result |
|---|---|---|
| R1 | Add the Riseup account with its password. | Folders appear. The inbox fills in about 1 minute. |
| R2 | From another account, send a mail to your Riseup address. Keep the screen on. | Notification in a few seconds. |
| R3 | Turn the screen off for 15 minutes. Then send another mail to Riseup. | Notification in a few seconds, with the screen still off. |
| R4 | In Plico, write a mail to yourself and send it. | It arrives once. Riseup Sent has **one** copy. |
| R5 | Open the received mail → menu → View headers. | No phone name, no app name, `Date` ends with `+0000`. |
| R6 | Archive, delete and mark as read in Plico. Then open Riseup webmail. | Webmail shows the same changes within seconds. |
| R7 | In webmail, read or move a mail. | Plico shows the change. |
| R8 | After 15 minutes: Settings → the Riseup account. | It shows the server keep-alive interval (e.g. 2 min). |

## Gmail (app password)

Make an app password at <https://myaccount.google.com/apppasswords> (needs 2-Step Verification).

| # | Do this | Expected result |
|---|---|---|
| G1 | Add the Gmail account with the app password. | Inbox, Sent, labels appear. |
| G2 | Send a mail to Gmail from another account. | Notification in a few seconds (Primary mail only, by default). |
| G3 | In Gmail on the web, archive a mail that Plico shows in its inbox. | The mail leaves Plico's inbox within about 1 minute. |
| G4 | In Gmail on the web, star a mail. In Plico, remove the star. | The star is gone in Gmail on the web too. |
| G5 | Leave the phone with the screen off for 1 hour. Then send a mail to Gmail. | Notification in a few seconds. |
| G6 | Send a mail from Plico. | Gmail Sent has **one** copy. |

## Battery

| # | Do this | Expected result |
|---|---|---|
| B1 | Next day: Android Settings → Battery → battery usage. | Plico uses a small share (about 1–3 % per day). |

## How to report

Tell me the result of each step, for example "R3: 4 s" or "G3: mail stayed in the inbox".

Optional: connect the phone with USB debugging and run `adb logcat --pid=$(adb shell pidof app.plico.debug)`.
Plico's log has only command names and status codes. It has no addresses, subjects or message text.
