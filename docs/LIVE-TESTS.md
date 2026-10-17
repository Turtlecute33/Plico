# Live tests on your phone

These tests check what only real servers can show (DESIGN.md §16). Use the **debug APK**
(`app.plico.debug`). It installs next to a later release build and keeps its own data.

Before you start:

1. Make sure that the phone runs Android 15 or later.
2. Install the APK. Open Plico and add your accounts: type the address and select Next. You type the passwords;
   nobody else sees them.
3. Allow notifications and the battery exemption when Plico asks.
4. Write down the time of each step. "Delay" means the time from sending a mail to its notification.

Do the tests marked "if available" only when you have such an account.

## Update

| # | Do this | Expected result |
|---|---|---|
| U1 | If an earlier debug build is on the phone: install the new APK over it. Do not remove the old one. | The Riseup and Gmail accounts stay and sync. Settings → each account → Servers shows the old servers (for example `mail.riseup.net:993 · SSL/TLS`). The server keep-alive interval (R8) stays as before. |

## Riseup

| # | Do this | Expected result |
|---|---|---|
| R1 | Add the Riseup account: type the address, select Next, type the password, select Sign in. | The password page shows the address and "Plico connects only to mail.riseup.net." Folders appear. The inbox fills in about 1 minute. |
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
| G1 | Add the Gmail account: type the address and select Next. Use the app password. | The "Google account" page opens. The app password works. Inbox, Sent, labels appear. |
| G2 | Send a mail to Gmail from another account. | Notification in a few seconds (Primary mail only, by default). |
| G3 | In Gmail on the web, archive a mail that Plico shows in its inbox. | The mail leaves Plico's inbox within about 1 minute. |
| G4 | In Gmail on the web, star a mail. In Plico, remove the star. | The star is gone in Gmail on the web too. |
| G5 | Leave the phone with the screen off for 1 hour. Then send a mail to Gmail. | Notification in a few seconds. |
| G6 | Send a mail from Plico. | Gmail Sent has **one** copy. |
| W1 | If available: add a Google Workspace address on your own domain. | The "Google account" page opens (Plico found Google through MX). The account uses Google's servers. |

## Other IMAP servers

X0 uses your esiliati.org account. Do X3 to X5 after X0, on the same account.

| # | Do this | Expected result |
|---|---|---|
| X0 | Add the esiliati.org account: type the address and select Next. | The password page shows "Plico connects only to imap.esiliati.org and mail.esiliati.org." Folders appear and the inbox fills. Settings → the account → Servers shows `imap.esiliati.org:993 · SSL/TLS` and `mail.esiliati.org:465 · SSL/TLS`. |
| X0a | From another account, send a mail to the esiliati.org address. | Notification in a few seconds. If setup said "This server cannot tell Plico about new mail immediately", the mail comes at the next check (every 15 minutes by default). |
| X0b | In Plico, send a mail from the esiliati.org account to yourself. | It arrives once. After about 1 minute, Sent has **one** copy. |
| X0c | In Plico, archive a mail, delete a mail, move a mail to Spam and then select Not spam. Then open the webmail. | The webmail shows the same changes. "Not spam" gives no new notification. |
| X0d | In Plico, open Trash and empty it. | Trash is empty in Plico and in the webmail. |
| X3 | Settings → the esiliati.org account → Change server settings. Set Incoming to port 143 with STARTTLS. Set Outgoing to `smtp.esiliati.org`, port 587, STARTTLS. Type the password and select Sign in. | Sign-in works. Servers shows STARTTLS for both. The folders sync again from the start. |
| X4 | Change server settings again. First set Outgoing to `smtp.esiliati.org`, port 587, SSL/TLS. Then set it to `mail.esiliati.org`, port 465, STARTTLS. Each time, type the password and select Sign in. | Each time, in at most about 20 seconds: "The server did not start an encrypted connection … Make sure that the port and the security setting are correct." The account does not change. |
| X5 | Change server settings back to `imap.esiliati.org`, 993, SSL/TLS and `mail.esiliati.org`, 465, SSL/TLS. | Sign-in works. The folders sync again. |
| X1 | If available: add a Fastmail, mailbox.org or Posteo account. Send one mail. | The password page shows its servers. Sync and send work. After about 1 minute, Sent has **one** copy. |
| X2 | If available: add an address of a domain that has only an autoconfig file (a domain that is not in the built-in list). | The password page shows the servers from the autoconfig file. |
| Y1 | If available: add a Yahoo or AOL account with an app password. Send one mail. | Sent has exactly **one** copy (the copy of the server; Plico does not upload one). |

## Outlook.com and Microsoft 365

All tests in this table: if available. You need your own Microsoft client ID (README, "Outlook.com and Microsoft
365 (optional)") and IMAP turned on in Outlook.com.

| # | Do this | Expected result |
|---|---|---|
| M1 | Add the Outlook.com account: type the address, select Next, sign in through Microsoft in the browser. | The "Microsoft account" page opens, then the browser, then Plico again. The inbox fills. |
| M2 | Send a mail from the Outlook.com account in Plico. | It arrives. Sent Items has **one** copy. If Microsoft does not let apps send mail (SMTP AUTH off), setup showed that note, and Plico still receives mail. |
| M3 | Leave the Outlook.com account for 2 hours. | It still syncs. No "Sign-in needed" alert comes. |
| M4 | Settings → the Outlook.com account. Then archive, delete and select Not spam in Plico, and look in Outlook on the web. | New mail shows a push mode (the server has IDLE). Outlook on the web shows the same changes. |
| M5 | With IMAP turned off in Outlook.com, try to add the account. | Setup stops before it saves the account: "Microsoft did not open the mailbox. In Outlook.com, turn on IMAP for devices and apps …". |

## Message list

| # | Do this | Expected result |
|---|---|---|
| L1 | Look at the message list in the light and the dark theme. | Unread mail: bold text, a dot before the date, a colour for each sender on the avatar. Read mail: lighter text and a neutral avatar. In "All inboxes", each avatar has a small dot in the colour of its account. |
| L2 | Settings → Message list → turn on Sender logos. Try each logo source. | DuckDuckGo and Google: logos for company senders, also for a first mail. Website of the sender: logos only for senders that wrote to you before. Letters for addresses at gmail.com and other mail providers, for your own sent mail, and in Spam. Opening a message never gets a new logo. A small icon and a large logo both fill the white circle. When you turn logos off, the logos go. |
| L3 | With logos on, turn on airplane mode and open the list. Then turn airplane mode off. | Letters while offline. The logos come back when the phone is online again (at the latest after 10 minutes). |

## Battery

| # | Do this | Expected result |
|---|---|---|
| B1 | Next day: Android Settings → Battery → battery usage. | Plico uses a small share (about 1–3 % per day). |

## How to report

Tell me the result of each step, for example "R3: 4 s" or "G3: mail stayed in the inbox".

Optional: connect the phone with USB debugging and run `adb logcat --pid=$(adb shell pidof app.plico.debug)`.
Plico's log has only command names and status codes. It has no addresses, subjects or message text.
