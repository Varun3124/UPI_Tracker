<h1>UPI-Tracker</h1>
This is a simple utility project (expense tracker) that aims to do little more than just expense tracking.<br/>
Offline storage and not reliant on internet/bluetooth...dependent on cellular network for receiving UPI-Bank SMS.

Two features use the network, and both are opt-in and off until you sign in with Google:

- **Google Drive backup** uploads an encoded copy of your data to a private folder in your own Drive
  that only this app can read.
- **The friends mailbox** delivers transactions you share with friends you have linked. Each message
  is signed and sealed on your phone (HPKE and Ed25519, through Google Tink) before it leaves, and
  Firebase Cloud Firestore only ever stores the sealed bytes. Turning the mailbox on stores your email
  address and a user ID with Firebase Authentication, and keeps a copy of your mailbox key in the same
  private Drive folder as the backup. You can delete the mailbox account from Settings → Friends
  mailbox; see [docs/account-deletion.md](docs/account-deletion.md).

Every other feature works with the radio off, and nothing is sent anywhere else.

<br/><br/>

Features:<br/>
1) Track income/expense
2) Minimize user effort for recording transactions (parse SMS (HDFC-credit/debit, AXIS-debit only) to extract UPI payments)
3) Track IOU with friends (exactly like Splitwise)
4) Balance tracking for pools of money(cash, savings, FD, investments)
5) Statistics (category wise expenditure)
6) Import transactions from bank statement(HDFC, .xls format)
7) Share transactions with friends: as a parcel pasted into any chat, or straight to linked friends, several at once, through the end-to-end encrypted mailbox (backend setup in [firebase/README.md](firebase/README.md))
8) Agree on a balance with a linked friend without sharing your whole history: either of you declares what you owe each other, the other accepts, and both books carry on from that checkpoint. Chapters (group IOUs) can be shared with their members, so everyone sees the same plan. See [docs/declarations-design.md](docs/declarations-design.md).

<br/>

Future implementation plans (subject to my interest):<br/>
>Settle up with linked friends by netting IOUs across a group
