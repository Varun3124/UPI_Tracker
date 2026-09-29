<h1>DhanMoney (UPI-Tracker)</h1>

An expense tracker for Android that aims to do a little more than track expenses: it records UPI payments
for you from bank SMS, keeps IOUs with friends like Splitwise, and lets friends agree on what they owe each
other without handing over their whole history.

Your data lives on your phone. Everything works offline; the only things that touch the network are two
opt-in features that stay off until you sign in with Google (see [Privacy](#privacy)).

**Current version: 1.4** · Android 10 or later

## Download

**[Download the latest APK](https://github.com/Varun3124/UPI_Tracker/releases/latest/download/DhanMoney-1.4.apk)**
(or see [all releases](https://github.com/Varun3124/UPI_Tracker/releases)).

1. Open the APK on your phone. Android asks you to allow installs from your browser or file manager the
   first time; allow it.
2. Updating from an earlier version keeps your data: install the new APK over the old one.
3. On first launch, grant SMS access so the app can record UPI payments as they happen. It works without
   it too; you then add transactions by hand or by statement import.

## Features

**Recording money**

1. Track income and expenses, split across categories, with refunds linked to the purchase they reverse.
2. Record UPI payments automatically from bank SMS: HDFC (credit and debit), ICICI (credit and debit) and
   Axis (debit). Axis alerts forwarded to Gmail are read from the notification as well. Anything the app is
   unsure about waits as *pending* for you to confirm, from the notification or the app.
3. Import transactions from a bank statement (HDFC, `.xls`), with duplicates found for you.
4. Track balances for pools of money: cash, savings, FDs, investments, and transfers between them.
   Balances from before your first recorded balance are marked as estimates.

**Friends and groups**

5. IOUs with friends, exactly like Splitwise: split any transaction between any people, on either side.
6. **Chapters** (group IOUs): put a trip or a flat's expenses in a chapter, see who owes whom, and the
   fewest payments that settle everyone up. A chapter's result feeds each friend's balance.
7. **Share transactions** with friends: as a parcel pasted into any chat, or straight to linked friends,
   several at once, through the end-to-end encrypted friends mailbox.
8. **Agreed balances**: agree on what you owe each other with a linked friend without sharing your whole
   history. Either of you proposes, the other accepts, and both books carry on from that checkpoint. Older
   transactions found later only count if you both agree to add them.
9. **Shared chapters**: share a chapter with its members so everyone sees the same plan. Linked members get
   a copy that follows your changes; anyone else can be sent a copy to paste. Only the chapter's creator
   can change it.

**Seeing where it goes**

10. Dashboard with today's, this week's and this month's net cash flow, recent transactions, IOUs and open
    chapters.
11. Statistics: spending by category (pie and stacked bars) with drill-down to who was paid, and trends of
    income against expense and your balance over time.

**Keeping it safe**

12. Back up to, and restore from, a private folder in your own Google Drive.

How agreed balances and shared chapters work is written up in
[docs/declarations-design.md](docs/declarations-design.md); chapters in
[docs/chapters-design.md](docs/chapters-design.md).

## Privacy

Two features use the network, and both are opt-in and off until you sign in with Google:

- **Google Drive backup** uploads a copy of your data to a hidden folder in your own Drive that only this
  app can read. The developer never receives it.
- **The friends mailbox** delivers what you share with friends you have linked: transactions, agreed
  balances and shared chapters. Each message is signed and sealed on your phone (HPKE and Ed25519, through
  Google Tink) before it leaves, and Firebase Cloud Firestore only ever stores the sealed bytes. Turning
  the mailbox on stores your email address and a user ID with Firebase Authentication, and keeps a copy of
  your mailbox key in the same private Drive folder as the backup.

Every other feature works with the radio off, and nothing is sent anywhere else. There are no ads,
analytics or trackers.

- Privacy policy: https://dhanmoney.web.app/privacy
- Deleting your mailbox account: Settings → Friends mailbox → Delete mailbox account, or see
  https://dhanmoney.web.app/account-deletion
- Contact: dhanmoney.feedback@gmail.com

## Building it yourself

Open the project in Android Studio, or run `./gradlew :app:assembleDebug`. A release build is signed when a
`keystore.properties` sits at the project root (it is gitignored); without one you get an unsigned release
APK. The mailbox needs a Firebase project; the one-off setup is in [firebase/README.md](firebase/README.md).

## Future plans (subject to my interest)

> Settle up with linked friends by netting IOUs across a group

## License

[MIT](LICENSE)
