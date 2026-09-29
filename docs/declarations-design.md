# Declarations and shared chapters: agreeing on an IOU without sharing every transaction

Design brief for **declarations** (agreed checkpoints of what two linked friends owe each other) and
**shared chapters** in UPITracker (DhanMoney). It is written to be handed to an engineer or an LLM as
full context, in the same shape as [chapters-design.md](chapters-design.md), which it extends. Read
sections 1–4 first; the rules in section 5 are the contract.

---

## 1. Summary

Two friends who both use the app keep two separate books. Today those books only agree if every
transaction between them has been passed across, by parcel or mailbox, and reviewed on both sides.
Nobody does that for years of history, so the two figures drift apart.

A **declaration** fixes that without the history. Either friend can say "as of now, you owe me
₹1,200". If the other accepts, both phones hold the same agreed figure: a **checkpoint**. From then on
each phone's balance is the checkpoint plus whatever happened after it. What came before no longer
has to add up, on either phone.

A declaration covers the **whole** balance between the two, chapters included. Chapters are live
books whose plans move whenever they change, and a plan can create a debt between people who never
transacted. So chapters become **shareable**. The chapter's creator stays the only editor. Every
linked member receives a copy that uses the creator's plan, so every phone agrees on who owes whom in
it.

### Goals
- Two linked friends can agree on one figure without exchanging their histories.
- Nothing changes on either phone without both friends' consent (a declaration), or the owner's
  edit of a chapter both friends are in (a shared chapter, which applies automatically).
- Once agreed, the two books stay agreed through every later transaction both of them hold, and
  every change to a chapter both of them see.
- Late or old rows, dated before the checkpoint, never quietly move an agreed balance.

### Non-goals
- A server that knows balances. The mailbox still only carries sealed messages.
- Several editors on one chapter. Members contribute by sending the owner their rows (S9).
- Agreement between people who are not linked. Declarations need a link; an unlinked friend can be
  handed a static copy of a chapter, and that is all.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Pair** | ME and one friend F whose `friend_links` row is `LINKED`. |
| **Whole balance** | What the friend page headline shows: base ledger (personal) + every chapter's contribution. `LedgerRepository.getSummaryForFriend().netBalancePaise`. |
| **Declaration** | A row in `iou_declarations`: "as of `asOfEpoch`, F owes ME `amountPaise`" (negative = ME owes F), held by **both** phones under the same id, each from its own seat. |
| **Proposal** | A request to change the pair's declarations: `DECLARE` (add one), `AMEND` (replace one with the same instant and a new amount), `REVOKE` (remove one). It changes nothing until the other side accepts. |
| **Checkpoint** (E_F) | The **effective** declaration for F: the live one with the greatest (asOfEpoch, id). Written X (amount) and T (asOfEpoch). |
| **Part** | A row in `declaration_parts`: how much of X one chapter's share was, from this phone's seat. |
| **Opening** | The `iou_entries` row a checkpoint becomes: `O = X − Σ parts of chapters this phone holds`. It has a `declarationId` and no `transactionId`. |
| **Sealed** | An untagged transaction that names F and is dated ≤ T_F. It posts nothing for F. |
| **Absorbed** | A chapter change caused only by rows dated ≤ T_F. It is added to that chapter's part for F instead of moving the balance with F. |
| **Owner** | A chapter's creator. Only the owner's phone edits it. |
| **Replica** | A member's copy of someone else's chapter: `shareMode = REPLICA` (live), `FROZEN` or `STATIC`. |
| **Snapshot** | What the owner sends a member: the chapter written from the member's seat, including the owner's plan. |
| **Claim** | A member's own transaction, matched by reference to a snapshot row and tagged into the replica. |
| **Hint** | The owner's part for a chapter in the owner's checkpoint with a member, sent in each snapshot so the member's part matches. |

---

## 3. What exists today (read before implementing)

Package root: `app/src/main/java/com/varun/upitracker/`. Money is `Long` paise; + means the friend owes
ME, the same sign as `IouEntry.amountPaise`.

- **Base ledger**: `iou_entries`, materialised at save time by `LedgerPostingService.postLedger`
  through a `LedgerPort`. Repayments settle the oldest unsettled entries first
  (`LedgerRepository.applyAgainstPositiveBalance` / `...NegativeBalance`). The net is the plain sum
  of every port call, whatever the order; order only decides which entries are flagged settled.
- **Replay**: `ledger/LedgerReplayer` deletes a set of friends' entries and reposts every untagged,
  reviewed transaction that names them, oldest first, through `ScopedLedgerPort`. It is the only
  correct way to rebuild entries after anything but a brand-new row. See the class comment.
- **Chapters**: see [chapters-design.md](chapters-design.md). A tagged transaction posts nothing to
  `iou_entries`. `chapter_balances` holds each chapter's contribution per friend, derived by
  `ChapterMath` from the plan. `ChapterRepository.applyChapterChangeInTransaction` is the one place a
  row moves between books. **`ChapterMath` breaks ties with ME first**, so two members running it on
  the same rows can get different plans.
- **Mailbox**: `data/mailbox`. `MailboxSync` collects, unseals (`MailboxCrypto`), checks the signature
  against the keys pinned in `friend_links`, and dispatches on `MailboxKind`. `LinkRepository` makes
  and breaks links; `sendControl` sends a small signed message to a linked friend. Parcels
  (`domain/parcel`) are written from the reader's seat by `ParcelPerspective.flipForRecipient`.
  Mailbox rows carry `shareRef` (sender) / `sharedRefId = "mbx:<uid>:<ref>"` (reader); pasted rows
  carry `sharedRefId = "<token>.<sourceId>"`.
- **Checkpoint precedent**: account balances already work this way: `balance_snapshot` plus movements
  since (`AccountRepository.getBalance`, `domain/BalanceConfidence`). Declarations are the same idea
  for IOUs, agreed by two people instead of observed by one.

Two bugs in today's code are fixed as part of this work, because the fix is the replay this design
needs anyway:
- **Re-saving an untagged repayment records it twice.** The save path deletes the row's own entries
  and reposts it. A repayment that fully settled older entries owns no entries, so nothing is
  deleted, while the older entries stay flagged settled. The repost then finds nothing to settle and
  records the whole amount again.
- **Deleting a repayment leaves the debts it settled marked settled**, so the balance ignores that
  the repayment is gone.

---

## 4. Worked examples

### 4.1 A declaration
Alice and Bob link. Alice's book says Bob owes her ₹1,200 (₹900 direct, ₹300 from her private chapter
"Flat"). Bob's book says he owes ₹1,150.

1. Alice's phone confirms the link and proposes **DECLARE X = +1,200 as of 10 Sep 14:05**. It lists no
   chapters, because "Flat" is private. It records Flat's ₹300 as a local part.
2. Bob sees: *"Alice asks you to agree: as of 10 Sep, 2:05 pm, you owe her ₹1,200. Your book says
   ₹1,150."* He accepts. His phone sends ACCEPT, then records the declaration (−1,200). He has no
   chapters with Alice, so there are no parts; his opening is −1,200.
3. Alice's phone collects the ACCEPT. Her opening is +1,200 − 300 = +900, and Flat still contributes
   +300. Both headlines now read ₹1,200.
4. On 12 Sep Bob pays Alice ₹200 and sends her the row. It is dated after T, so it counts on both
   phones: ₹1,000 each. On Alice's phone the repayment settles the opening entry, oldest first.

### 4.2 A late row
On 15 Sep Alice finds a dinner from 2 Sep (Bob owes her ₹400) that she never recorded. She enters it.
It is dated before T, so it is **sealed**: nothing moves. Her entry screen says so, and offers *"Ask
Bob to add it"*. That is an **AMEND** of the 10 Sep declaration by +400: X′ = 1,600, T unchanged. If
Bob accepts, both phones add 400. If he denies, both stay as they were.

### 4.3 A chapter change before the checkpoint
Alice tags an old 5 Sep taxi (Alice paid, split with Bob) into Flat. Without absorption, Flat's share
would rise by the taxi's ₹150 while the taxi, sealed, posts nothing. That is harmless on its face, but
the ₹150 was already inside the agreed ₹1,200, so Alice's figure would become ₹1,350 and disagree with
Bob's. Instead the change is **absorbed**: Flat's part for Bob becomes 450, the opening drops to 750,
and the headline stays at ₹1,200.

### 4.4 A shared chapter
Alice shares "Goa" with Bob and Dan, both linked to her. She paid the hotel (₹9,000, split three
ways). Bob paid dinner (₹3,000, split three ways) and sent it to her. Dan paid a cab (₹600, split with
Bob).

- Nets: Alice +₹5,000, Bob −₹1,300, Dan −₹3,700. Alice's plan: Dan pays Alice ₹3,700; Bob pays Alice ₹1,300.
- Bob's phone gets a snapshot: *you owe Alice ₹1,300.* His own dinner row is claimed into the replica
  (matched by its `shareRef`), so it stops posting to his direct ledger. Both books show the same
  Goa figure.
- Bob and Dan never transacted directly. If the plan had routed a payment between them, both of their
  phones would show it the same way, because both use Alice's plan.

---

## 5. Rules (the contract)

### Declarations
- **D1. Linked pairs only.** A declaration is created or changed only by a proposal sent through the
  mailbox (signed and sealed like any message) to a `LINKED` friend. Three kinds:
  - `DECLARE` — a new checkpoint as of the moment it is made. The amount starts as my whole balance
    with F and can be edited before sending.
  - `AMEND` — replaces a live declaration with one at the same T and amount X + Δ. This is how a late
    row is incorporated (D10).
  - `REVOKE` — removes a live declaration. The previous one, if any, becomes effective again.

  Archived declarations (D12) cannot be amended or revoked.
- **D2. Consent.** A proposal changes nothing on either phone until its recipient accepts.
  - Accepting sends ACCEPT **first** and applies it after, so a failed send changes nothing.
  - The proposer applies it when the ACCEPT arrives.
  - Deny sends DENY; both sides close the proposal.
  - The proposer may withdraw an unanswered proposal. One never sent is simply deleted.
- **D3. Accept beats withdraw.** A withdraw closes the proposal only if the recipient has not
  accepted it. An ACCEPT that reaches the proposer after they withdrew still applies. The recipient
  only sends ACCEPT for a proposal that was open on their phone, so both phones end in the same state.
- **D4. Order never matters.** The accepted proposals of a pair form a two-phase set: a declaration id
  is added once (`DECLARE`, `AMEND`) and removed at most once (`AMEND`'s target, `REVOKE`'s target),
  and a removal is permanent. Applying the same accepted proposals in any order gives the same live
  set on both phones.
  - Out-of-order delivery is harmless: a withdraw for a proposal not yet seen leaves a stub row, and
    the proposal is ignored when it arrives.
  - Every message is applied at most once; its id is the `mailbox_messages` primary key.
- **D5. Effective checkpoint.** E_F is the live declaration with the greatest (asOfEpoch, id), ids
  breaking ties. Archived ones count. Both phones hold the same live set, so they pick the same one.
- **D6. Whole balance, split into parts.** X is the proposer's whole balance at T: direct(T) plus
  Σ chapter shares at T. Each phone records which chapter shares X included, as **parts**:
  - **Shared chapters** (a `shareId`, with the counterparty in the plan) are listed in the proposal
    with their share, written from the reader's seat. The acceptor adopts exactly those figures, so
    the pair agrees on them. A listed chapter the acceptor does not hold yet is kept as a pending
    part (no `chapterId`) and attached when its replica arrives.
  - **Private chapters** (no `shareId`) are never listed. Each phone records its own: the proposer's
    share at proposal time, the acceptor's at accept time. The friend cannot see a private chapter,
    so this is the only way the agreed figure can include it.
  - **A shared chapter the proposer did not list** has no part: it was not in X, so it counts in full
    on both phones.
- **D7. The balance formula.**

  > B(F) = X + Σ effects of untagged rows naming F dated > T + Σ_c (C_c(F) − part_c(F))

  It is implemented as an **opening entry**: O = X − Σ parts of the chapters this phone holds, posted
  into F's `iou_entries` before anything else. Everything else is unchanged:
  - Chapters keep writing `chapter_balances`.
  - The read path is still personal + Σ chapters.
  - Repayments after T settle the opening first, as the oldest debt there is.

  With no checkpoint, B(F) is exactly what it is today.
- **D8. Sealing.** An untagged row naming F and dated ≤ T posts nothing for F, however late it is
  recorded: typed in, imported from SMS, reviewed from pending, or received by parcel. The checkpoint
  already stands for everything before it.
  - Editing or deleting a sealed row moves nothing.
  - Moving its date across T does move the balance. The entry screen warns first.
- **D9. Absorption (D8 for chapters).** Some chapter changes are caused only by rows whose every
  version is dated ≤ T_F: a tag, untag, move, save, or delete. Such a change does not move the balance
  with F. It is added to that chapter's part for F instead (creating the part if needed), and the
  opening shrinks by the same amount.
  - Rows dated after T_F change chapters as today.
  - On a shared chapter, the owner absorbs for each member and says so in the next snapshot's hint
    (S8).
- **D10. Incorporating a late row.** The effective checkpoint is amended by Δ: the row's effect on F,
  measured by running it through `postLedger` into a recorder. It can be edited before sending. On
  acceptance both phones hold X′ = X + Δ at the same T. The late row stays sealed; the amendment
  carries it.
- **D11. Automatic on linking.** When a link completes, the phone that confirms it (the inviter's)
  proposes a `DECLARE` of its current whole balance, marked automatic. The proposal is fixed when it
  is made and waits in an outbox, retried at every mailbox check until it is sent. Either friend can
  propose at any time afterwards.
- **D12. Unlinking archives.** When a pair unlinks, either side's open proposals close. This happens
  when this phone unlinks, when it collects an `UNLINK`, when it signs in to a different mailbox
  account, and when it deletes its account.
  - Its accepted declarations become **archived**. They still anchor the balance, so the IOU does not
    change, but no protocol can change them.
  - Relinking proposes a fresh declaration (D11), which supersedes them.
- **D13. What the acceptor sees.** The proposed figure and its breakdown (direct, and each listed
  chapter). Also: their own current whole balance, what it becomes if they accept (X plus their own
  rows dated after T, plus any unlisted shared chapter), and a warning about their own pending rows
  naming the proposer dated ≤ T, which will be sealed once reviewed.
- **D14. Friend merge.** Declarations and their parts follow the friend. The merged friend's base
  ledger is replayed, so the other alias's older rows become sealed. The merge screen warns.

### Shared chapters
- **S1. One owner.** A chapter's creator owns it. Only the owner's phone changes its rows, members,
  name, state or plan. Sharing is an explicit **Share with members**, which gives the chapter a
  random `shareId`.
- **S2. Delivery.**
  - Every **linked** member receives a live **replica** automatically, with no consent step, and
    keeps receiving the owner's changes.
  - The owner's phone sends a snapshot to each linked member whose copy is behind: after any change,
    at the next mailbox check, when the chapter screen closes, and after a save into the chapter.
    Everything but the mailbox check goes through one background job (`ChapterPublishing`), queued
    after a save, delete or tag that touched a shared chapter and on leaving its screen, so a send
    survives the screen that caused it closing.
  - Anyone, linked or not, can be pasted a **static** copy through a chat app. It never updates.
  - The owner's `shareVersion` counts every change to every chapter the owner holds, shared or not.
    A copy pasted later therefore always reads as newer than one pasted before, even from a chapter
    that was never shared live.
- **S3. Snapshots.** A snapshot is written per recipient, flipped like a parcel (the owner is Sender,
  the reader is Me). It carries:
  - name, notes, state and a version
  - the members; the nets; the owner's **plan**
  - the chapter's rows, except the owner's solo spending (no one else in the row), each with the
    references a claim can match on
  - the hint (S8)

  **Members never recompute the plan.** `ChapterMath`'s ME-first tie-breaks would give each member a
  different one.
- **S4. A member's balances** come from the plan's payments between ME and a person resolved to a
  local friend:
  - the Sender is the linked friend
  - `Linked(uid)` is my own friend linked to that account, if any
  - anyone else is resolved by a mapping I make by hand (stored in `chapter_people`)

  A payment to someone unresolved is shown but not counted. The resolved contributions are written to
  `chapter_balances`, so friend pages and the dashboard read them as they read any chapter's.
- **S5. Claims.** My own copies of the chapter's **reviewed** rows are tagged into the replica
  automatically, so they stop posting to my direct ledger and are not counted twice. Matching is by
  reference only, never by name or amount:
  - my `shareRef` against the owner's `mbx:<me>:<ref>`
  - the owner's `shareRef` against my `mbx:<owner>:<ref>`
  - a third party's `mbx:` reference, as-is
  - paste references `<token>.<id>` in either direction

  A row the owner drops is untagged again. A matching row that sits in one of my own chapters is not
  moved; the replica flags it.

  Claiming goes by the verified accounts; letting go does not. A row already claimed stays while the
  owner's snapshot still carries it, found by its random reference alone. Otherwise a copy that has
  lost its accounts (frozen by an unlink, then replaced by a pasted copy) would release rows the owner
  still counts, and they would count twice.

  The copy's screen marks each of its rows the same way (`ClaimMatcher.locate`): *in your book*;
  *also in your <chapter>: take it out there, or it counts twice*; *awaiting review on the owner's
  phone*; *in your book, outside this chapter*; or *only in the owner's book*. Tapping a row I hold
  opens my own copy of it.
- **S6. Read-only.** A replica cannot be edited, tagged into by hand, or used as the active chapter.
  My claimed rows stay mine to edit, but edits do not change the replica, whose plan is the owner's.
- **S7. Lifecycle.**

  | Event | Result on the member's phone |
  |---|---|
  | Owner or member unlinks; owner stops sharing | `FROZEN`: last snapshot kept, still feeding balances |
  | Relink, or the owner shares again | Live again (same `shareId`) |
  | Owner deletes the chapter | Replica removed, claims untagged, base ledger replayed |
  | Member removes a frozen or static copy | Same as above, after a warning that balances may change |

  A snapshot never replaces a newer one, a pasted copy never replaces a live one, and a copy is
  only ever updated by the friend it came from. Someone else naming its `shareId`, or the owner's
  own chapter coming back pasted, changes nothing.

- **S8. Hints.** Each snapshot carries the owner's part for this chapter in the owner's checkpoint with
  the recipient: its `declarationId` and amount, from the recipient's seat. If the recipient holds
  that declaration, it sets its own part to match. This keeps an (owner, member) pair agreed after the
  owner absorbs a change (D9), and when a chapter that was private when the pair agreed is shared
  later.
- **S9. Contributing.** A member adds to someone else's chapter by sending the row to its owner through
  the existing Share flow. The owner tags it in, and the next snapshot claims the member's copy.
- **S10. Pickers show own chapters only.** The entry screen's chapter field, the settle-up prompt
  (R18), pre-selection (R19) and the active chapter (R10) only offer chapters this phone owns.

### What can still differ between two books
The UI marks each of these as *only in your book*:
- a private chapter's changes after the checkpoint
- rows dated after the checkpoint that only one side holds
- a replica payment whose counterparty the member has not mapped
- a third party's chapter only one of the two receives

A new declaration brings the books back together.

---

## 6. Why the two books agree

Take a pair (A, B) and one effective declaration, with A's seat giving X and B's giving −X. Write
parts in A's seat.

A's balance with B is:

> X + P_A(>T) + Σ_{c ∈ A} (C_c − partA_c)

B's balance with A, negated, is:

> X − P_B(>T) − Σ_{c ∈ B} (C_c^B − partB_c)

The two are equal term by term when both phones hold the same things:
- **Rows after T.** The same rows, passed across by parcel or mailbox, give P_A = −P_B, because
  `IouLegs` derives the same legs from either seat.
- **Chapters both phones hold.** Both use the owner's plan (S3), so C_c = −C_c^B. Their parts are
  equal too:
  - listed in the proposal (D6), or
  - absent on both sides, or
  - set from the owner's hint (S8).
- **Chapters only one phone holds.** For a private chapter the part is that phone's own share at
  agreement time. The chapter's later changes move only that book, which is what "private" means.

Everything before T is represented only by X, which both phones hold. Sealing (D8) and absorption
(D9) make sure nothing dated before T can reach the balance any other way.

---

## 7. Protocol

New `MailboxKind` values, inside the sealed envelope. `firebase/firestore.rules` does not change: it
never sees a kind.

| Kind | Body (base64url of a `DataOutputStream`, like `LinkMessages`) |
|---|---|
| `DECLARATION_PROPOSAL` | magic, id, kind, targetId, asOfEpoch, amount (reader's seat), delta (reader's seat), proposedEpoch, auto, note, parts `[(shareId, amount)]` |
| `DECLARATION_ANSWER` | magic, proposal id, `ACCEPT` / `DENY` / `WITHDRAW`, note |
| `CHAPTER_SNAPSHOT` | S3; the rows as an embedded ParcelFormat v2 body, plus each row's references; kept under `MailboxEnvelopeFormat.MAX_BODY_BYTES`, rows involving the reader first |
| `CHAPTER_ENDED` | shareId, `UNSHARED` / `DELETED` |

Handling rules:
- **Who may send what.** Proposals and answers are accepted only from the pair's linked account,
  verified against the pinned keys. An answer must name a proposal this phone made to that friend.
  An `AMEND` or `REVOKE` must name a declaration of that pair.
- **Idempotency.** Every handler can safely run twice. The message id is recorded
  (`insertMessageIfNew`), and every state change is a no-op when already applied.
- **One question at a time.** A phone has at most one open proposal of its own per friend. A second
  waits until the first is answered or withdrawn. Answering the friend's proposal is always possible.
- **Checked before sealing.** Nothing is sealed to a friend until the keys they publish have been
  compared with the ones pinned at linking (`SealedDelivery`). If they differ, nothing is sent and the
  link is marked `KEY_CHANGED`. An automatic proposal waits in the outbox; one the user made is
  refused with the reason.
- **Unknown kinds.** A kind this version does not know is recorded as unreadable with *"update
  DhanMoney to read this"*, rather than *"ask them to send it again"*.
- **Static copies.** `UPIC1.<crc32>.<payload>`, framed by `PasteFraming` like a parcel. The payload is
  the `CHAPTER_SNAPSHOT` body, DEFLATE-compressed by `PasteCompression`, which `ParcelCodec` shares.
  A paste verifies nobody, so a static copy names no one by account and carries no hint. One that does
  is refused, both when it is written and when it is read. It is pasted into the same box as parcels
  and invites, with the sender picked by the reader. Before anything is kept, a preview shows what the
  copy would count and against whom, who in it is not placed yet, and how many of the reader's own rows
  it would take in.

---

## 8. Ledger mechanics

### 8.1 The opening and the replay
`LedgerReplayer.replay(friends)`:
1. Reads each friend's checkpoint and opening.
2. Reads the rows, with an SQL floor of the smallest T among the friends.
3. Deletes their entries.
4. Records each non-zero opening through `LedgerPort.recordOpening`.
5. Posts every row through a port scoped to the friends for whom that row is not sealed.

`IouEntry.transactionId` becomes nullable and `IouEntry.declarationId` is added. The three
oldest-first queries use a `LEFT JOIN` on `transactions` and put openings first. `settleEntry` keeps
`declarationId` on a residual.

### 8.2 When the base ledger is rebuilt
- **Tag, untag, move, chapter delete.** As today (chapters-design 7.2).
- **Edit of any existing transaction.** Replay everyone it names, before and after, instead of
  deleting and reposting. This fixes the re-save bug in section 3.
- **Delete** (and conversion to an account transfer). Replay everyone it named, through one helper,
  `TransactionRemoval`. This fixes the delete bug in section 3.
- **Review from a notification.** Replay the row's friends.
- **A checkpoint changing** (accepted, amended, revoked, merged), **a part changing** (absorption,
  hint, replica arriving or leaving), or **a claim**. Replay the friend(s) concerned.

A **brand-new** row is still posted directly, through a port that drops calls for friends it is sealed
for.

### 8.3 Absorption
`ChapterRepository` reads the touched chapters' `chapter_balances` before and after recomputing. For
every friend whose effective T is ≥ the date of every version of the rows that moved (the row itself,
old and new, and any refunds carried with it), it adds the difference to that chapter's part in their
checkpoint. Those friends are then replayed so their opening follows.

---

## 9. Data model

**Migration 20 → 21 (declarations).** Guarded like the others; copy the `CREATE` statements from the
generated `21.json`.

```kotlin
@Entity(tableName = "iou_declarations", foreignKeys = [friendId -> friends CASCADE], indices = [friendId])
data class IouDeclaration(
    @PrimaryKey val id: String,         // 22-char random, identical on both phones
    val friendId: Long,
    val kind: String?,                  // DECLARE | AMEND | REVOKE; null only for a withdraw stub
    val targetId: String?,              // AMEND / REVOKE
    val asOfEpoch: Long?,               // DECLARE / AMEND
    val amountPaise: Long?,             // DECLARE / AMEND: X, + friend owes ME
    val deltaPaise: Long?,              // AMEND: the Δ, for display
    val note: String?,
    val proposedByMe: Boolean,
    val proposedEpoch: Long,
    val state: String,                  // OPEN | ACCEPTED | DENIED | WITHDRAWN | CLOSED
    val decidedEpoch: Long?,
    val archived: Boolean,
    val sentEpoch: Long?,               // outgoing only; null = waiting in the outbox
    val auto: Boolean,
    val replyNote: String?
)

@Entity(tableName = "declaration_parts", foreignKeys = [declarationId CASCADE, chapterId CASCADE])
data class DeclarationPart(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val declarationId: String,
    val chapterId: Long?,               // null while a listed chapter's replica has not arrived
    val shareId: String?,               // null for a private chapter
    val amountPaise: Long               // this phone's seat
)
```

`iou_entries` is rebuilt (copy, drop, rename, as `MIGRATION_8_9` does for `transactions`):
`transactionId` becomes nullable, and `declarationId` is added (FK → `iou_declarations`, CASCADE)
with an index.

**Migration 21 → 22 (shared chapters).**
- `chapters` gains `shareId` (unique), `ownerFriendId`, `shareVersion`, `snapshot` (the last
  snapshot body, replicas only) and `shareMode` (`PRIVATE` | `SHARED` | `REPLICA` | `FROZEN` |
  `STATIC`).
- `chapter_shares` (owner side): `chapterId`, `friendId`, `sentVersion`, `active`.
- `chapter_people` (member side): `chapterId`, `personKey`, `friendId`.

Every column is INTEGER or TEXT, so `DatabaseDumpRepository` backs the new tables up unchanged. A
restore from before either migration lands with no declarations, openings or replicas, which is
exactly the old behaviour.

---

## 10. UI

- **Friend page (linked friend).** An agreement line, which is one of:
  - *"Agreed ₹1,200 on 10 Sep"*, with **Agree on a balance**, and **Remove** on the effective one
  - *"Waiting for Bob to agree to ₹1,200"* · **Withdraw**
  - *"Bob asks you to agree to ₹1,200"* · **Review**, which opens the D13 dialog

  Untagged rows dated ≤ T are muted with the note *"Covered by the 10 Sep agreement"*. Archived
  checkpoints show as *"Agreed 10 Sep (while linked)"*.
- **Inbox.** A *Balances to agree* section in the style of link replies.
- **Notifications.** They say who, never amounts:
  - *"Bob asked you to agree on your balance"*
  - *"Bob agreed to your balance"*
  - *"Alice shared Goa trip with you"*
- **Entry screen.** For a sealed row, a banner: *"Dated before your agreed balance with Bob (10 Sep),
  so it doesn't change what you owe each other."* Two actions follow:
  - **Ask Bob to add it** (D10).
  - A warning when a date edit crosses T.
- **Chapter screen (owner).** The overflow offers **Share with members**, which says what goes out
  before anything does. Once the chapter is shared it offers **Members' copies** instead: each member
  as *has it as it stands*, *gets it at the next mailbox check*, or *not linked · tap to send a copy*,
  with **Stop sharing**. **Send a copy to paste…** is there for any member, shared or not. Deleting a
  shared chapter warns that members' copies go too.
- **Chapter screen (a friend's chapter).** Read-only. The header says whose it is, how it stands
  (*kept up to date by them*, *stopped updating*, or *a copy from 12 Sep*), as of when, and how many
  older rows were left out.
  - Balances: the owner's nets, the owner's plan, *Not counted yet* for payments with someone not yet
    placed, and *Who's who* to place people by hand.
  - Rows: the owner's, each marked as in S5.
  - **Remove copy** only when frozen or static. No Add, no active toggle, no selection.
- **Chapters list.** Badges: *Alice's · live*, *Alice's · frozen*, *Alice's · copy*; the user's own
  show *Active* and *Shared*. A friend's chapter shows the owner's nets, never ones worked out here.
  So does the dashboard's chapter slider.
- **Paste box (Settings).** Reads a static copy as well as parcels and invites. The reader picks the
  sender and sees the preview (§7), then **Keep copy** opens it.
- **Entry screen.** A row in a friend's chapter reads *"Alice's Goa trip"*, with *"Alice keeps this
  chapter…"* under it, and the chapter field cannot move it (S6). The picker offers the user's own
  chapters only (S10).

---

## 11. Edge cases

| Case | Behaviour |
|---|---|
| Both friends propose at the same moment | Two open proposals, one each way. Either or both may be accepted. The later (asOfEpoch, id) is effective on both phones (D5). |
| Proposal accepted while the proposer withdraws | Accept wins (D3). Both end with it accepted. |
| Withdraw arrives before its proposal | A withdrawn stub is recorded, and the proposal is ignored (D4). |
| `AMEND` and `REVOKE` of the same declaration both accepted | Both remove it. The amend's replacement is added. The result is the same in any order (D4). |
| Old rows arrive by parcel after the checkpoint | They land pending, and are sealed once reviewed: nothing moves (D8). |
| A pending row dated before T is reviewed later | Sealed. The proposal and accept dialogs warn about pending rows first (D13). |
| A row's date is edited across T | Counts from its new date. Warned. |
| Tagging a sealed row into a chapter | Allowed. Absorbed, so the balance with that friend does not move (D9). |
| Deleting a chapter holding rows before T | Its parts go with it; returned rows are sealed or count by date. The balance stays as agreed (§8). |
| Unlink, then relink | Archived declarations keep anchoring. Relinking proposes a fresh one. Frozen replicas resume. |
| Friend merge where one alias has a checkpoint | The merged friend is replayed, and the other alias's older rows are sealed (D14). |
| Member has not mapped a person in a replica | That payment is shown, not counted. |
| Member's own row is in their own chapter and in the owner's | Not claimed. The replica flags it. |
| Restore from an older backup | No declarations or replicas. Balances are as they were. |

---

## 12. Tests

JVM, under `app/src/test/`:
- **`DeclarationSetTest`**: live set; effective choice and tie-break; archived still counts; every
  permutation of the same accepted proposals gives the same result.
- **`DeclarationFlowTest`**: every transition, including accept-beats-withdraw, a withdraw stub, and
  closing on unlink.
- **`DeclarationConvergenceTest`**: both phones end in the same set under shuffled, duplicated and
  reordered delivery.
- **`CheckpointsTest`**: opening = X − parts held; the sealing filter per friend.
- **`DeclarationPartsTest`**: which chapter shares each phone counts (D6).
- **`AbsorptionTest`**: which friends absorb for a given set of row versions.
- **`LedgerReplayerTest`** (extended) and **`ReplayBookkeepingTest`**: opening first; sealed rows
  skipped per friend only; a repayment settles the opening; the SQL floor.
- **Regressions**: a re-saved repayment and a deleted one leave the correct balance.
- **Codecs**: `DeclarationMessagesTest`, `ChapterSnapshotTest` and `ChapterPasteCodecTest`:
  round-trips, and every rejection, including a paste that names someone by account or carries a hint.
- **`ReplicaAndClaimTest`**: contributions from a plan (S4); claims by every kind of reference, and
  locating rows without the accounts (S5).
- **`ThreeBookSimulationTest`**: in-memory books for Alice, Bob and Dan built only from the domain
  objects. It asserts that each pair's balances are negatives of each other across declarations, late
  rows, amendments, absorptions, shared chapters, private → shared, a lagging replica, unlink and
  relink.
