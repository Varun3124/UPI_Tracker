# Chapters: private group IOU tracking and simplification

Design brief for implementing **chapters** in UPITracker (DhanMoney). It is written to be handed to an
engineer or an LLM as full context. Read sections 1–4 before touching code; the rules in section 5 are
the contract, and the code pointers in section 3 are where the existing behaviour lives.

---

## 1. Summary

A **chapter** is a private, named container for tracking who owes whom among a group of friends:
a trip, a flat, a party. It holds **members** (friends) and **transactions tagged to it**. Tagged
transactions may involve only friends and not the user at all, for example "Dan paid the cab, split with Rahul".

A chapter works out every member's net position from its own transactions only. It then reduces
them to the **fewest payments** that settle everyone: the **plan**. The plan's payments that involve
the user feed the user's ordinary friend balances (the **base ledger**). So if a chapter says "Dan
pays you ₹700", Dan's balance with the user rises by ₹700, even if the two never transacted directly.

Everything is local to the user's phone. Friends don't need the app, aren't asked to consent, and
see nothing unless the user tells them.

### Goals
- Track IOUs among a group, including friend↔friend debts the base ledger cannot hold today.
- Simplify the group's debts to the fewest payments.
- Keep the base ledger correct: a friend's balance = personal IOUs + their share of every chapter's plan.
- Tagging, untagging and editing, in any order and after the fact, never corrupt balances.

### Non-goals for v1
- Sharing or syncing chapters with friends (see section 12).
- Exporting a chapter summary as text or CSV. The user explicitly deferred this.
- Per-chapter spending statistics.
- Multiple currencies.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Base ledger** | The existing `iou_entries` table. It only holds ME↔friend debts. |
| **Chapter** | Row in `chapters`. The user (ME) is always an implicit member and is not stored. |
| **Member** | A friend in `chapter_members`. |
| **Tagged transaction** | A `transactions` row with `chapterId` set. A transaction is in at most one chapter. |
| **Leg** | `IouLeg(debtor, creditor, amountPaise)` from `domain/iou/IouLegs.kt`. |
| **Net** | For one party in a chapter: sum of legs where they are creditor, minus sum where they are debtor. Positive = they are owed. |
| **Plan** | The simplified list of payments `debtor → creditor, amount` that zeroes every net. |
| **Contribution** | The plan's payments involving ME, per friend. Positive = the friend owes ME, the same sign as `IouEntry.amountPaise`. Stored in `chapter_balances`. |
| **Personal balance** | The base ledger alone: the sum of unsettled `iou_entries` for a friend. |
| **Friend balance** | Personal balance + the sum of that friend's contributions across all chapters. This is what the dashboard and friend page show. |

---

## 3. What exists today (read before implementing)

Package root: `app/src/main/java/com/varun/upitracker/`. Money is always `Long` paise. Actor types are
string constants in `ui.ActorType` (`ME`, `FRIEND`, `MERCHANT`, `UNKNOWN`).

**Transactions and shares**
- `database/entity/Transaction.kt`: payer and payee each have `*ActorType`, `*FriendId`, `*MerchantId` and `*RawLabel`. Other relevant fields are `isPending`, `ledgerEffect` (`DEBT` or `NONE`; `NONE` = gift, never touches IOUs), `iouRecovery`, `refundsTransactionId` and `dateEpoch`.
- `database/entity/TransactionShare.kt`: `side` (`"PAYER"`, `"PAYEE"` or null for legacy), `participantType`, `friendId`, `rawLabel` (a name nobody has been mapped to), `amountPaise`, `keepPayeeLeg`, `keepPayerLeg`.
- Transactions where ME is neither payer nor payee already exist, e.g. a friend paid a shop and split it. Today their friend↔friend legs are simply thrown away.

**The leg maths, which chapters reuse unchanged**
- `domain/iou/IouLegs.kt`:
  - `legs(payer, payee, shares, amount, ledgerEffect, recovery)` returns every kept leg, including friend↔friend ones and legs to a shop (`IouParty.Counterparty`).
  - `resolve(tx, shares)` gives the recovery to pass in.
  - `netByFriend(legs)` keeps only ME↔friend legs; that's how the base ledger ignores everything else.
  - `partyOf(actor)` maps a FRIEND with no id to `IouParty.Person(label)`.
- `IouParty` values: `Me`, `Friend(id)`, `Person(label)` (unidentified), `Counterparty(label)` (a shop; never an IOU).
- A direct payment with **no shares** yields **no legs** from `IouLegs.legs`.

**Posting to the base ledger**
- `domain/transactionentry/persistence/LedgerPostingService.postLedger(...)`:
  - returns early for `NONE`.
  - special-cases direct payments with no shares: FRIEND→ME calls `applyRepayment` and ME→FRIEND calls `applyOutgoingSettlement`.
  - everything else goes through `IouLegs.legs` and then `netByFriend` into `recordBalanceChange`.
- `data/repository/LedgerRepository.kt`: IOUs are **materialized** as `iou_entries` rows at save time.
  - Repayments settle the oldest entries first (`applyAgainstPositiveBalance` / `applyAgainstNegativeBalance` and `settleEntry`). They mark entries `isSettled = true`, split partial ones into a residual row, and record only the part left over as a new entry.
  - Net = `SUM(amountPaise) WHERE isSettled = 0` (`IouDao.getNetBalanceForFriend`, `getAllNetBalances`).
  - The net comes out as the plain sum of every posted change whatever the order; order only decides *which* entries end up flagged settled.
- Write paths that call `postLedger`:
  - `TransactionPersistenceService` (entry screen save). It deletes the transaction's `iou_entries` and reposts.
  - `sms/receiver/PendingTransactionReviewer.review` (one-tap "confirm" from a notification, via `PendingTransactionReviewReceiver`). It only runs when `PendingReviewRules.canAutoReview` passes.
  - `ParcelImportRepository.balanceDeltas` only posts into an in-memory `DeltaRecorder`, for previews. Reuse that pattern in tests.

**Where friend balances are read**
- `LedgerRepository.getSummaryForFriend` / `getAllSummaries`. Callers are `ui/dashboard/DashboardViewModel` (`iouSummaries`) and the friend page view model in `ui/ScreenViewModels.kt` (`FriendDetail…load`).
- `ui/FriendDetailActivity.kt` lists `transactionDao().getTransactionsForFriendSync(friendId)`, and reads each row's IOU via `iouDao().getEntriesForTransaction`.
- `data/repository/SettingsRepository.kt`: friend merge (`mergeFriendInto` reassigns every table) and `friendHasHistory` (blocks deleting a friend who has history).

**Database**
- `database/AppDatabase.kt`, currently `version = 19`. The previous change added `account_transfer.upiRefId`; if that isn't merged when you start, renumber. Migrations follow the `hasColumn` pattern, `exportSchema = true`, and schemas are written to `app/schemas/`.
- Backups (`data/backup/DatabaseDumpRepository.kt`) dump every table listed in `sqlite_master`, so new tables are included automatically. Restores go through `domain/backup/ColumnMapping.kt`. **Check that restore inserts parent tables before children** for the new foreign keys.

**Conventions to follow**
- Business rules live in pure `object`s under `domain/…` with JVM unit tests (`app/src/test/...`), e.g. `PendingReviewRules`, `ParcelEligibility`, `ImportedTransferMatcher`.
- Repositories and DAOs do the IO.
- KDoc explains *why*, not what.
- Multi-step writes use `db.runInTransaction { runBlocking { … } }` or `db.withTransaction { … }`.

---

## 4. Worked example

Chapter "Goa trip", members Rahul (R), Priya (P) and Dan (D), plus ME.

| # | Transaction | Legs |
|---|---|---|
| 1 | ME paid the hotel ₹9,000, split ME/R/P ₹3,000 each | R owes ME 3,000; P owes ME 3,000 |
| 2 | P paid dinner ₹3,000, split ME/P/D ₹1,000 each | ME owes P 1,000; D owes P 1,000 |
| 3 | D paid a cab ₹600, split R/D ₹300 each (ME not involved) | R owes D 300 |

Nets: ME **+5,000**, R **−3,300**, P **−1,000**, D **−700**. They sum to 0.

Plan (3 payments, the minimum for 4 parties with non-zero nets): R → ME 3,300 · P → ME 1,000 · D → ME 700.

Contributions fed to the base ledger: R +3,300, P +1,000, **D +700**. Dan now owes the user ₹700
although the two never transacted. That's the intended effect of simplification. Without chapters the
base ledger would hold R +3,000 and P +2,000, and the friend↔friend debts would be lost.

---

## 5. Rules (the contract)

### Membership and tagging
- **R1. A transaction is in at most one chapter** (`transactions.chapterId`, nullable, null by default).
- **R2. ME is always a member** and is never stored in `chapter_members`.
- **R3. No consent.** Creating a chapter and adding members is purely local.
- **R4. Eligibility to tag**, as a pure function `ChapterEligibility.blockedReason(tx, shares, chapter, originalOfRefund)` that returns null or a short reason. The transaction is refused when:
  - the chapter is closed: "Chapter is closed".
  - it is ME→ME (the shape of an account transfer): "Between your own accounts". `AccountTransfer` rows are never taggable.
  - no friend appears anywhere (payer, payee or a sided share): "No friends in this". Solo and merchant-only spending stays out of chapters in v1.
  - any person in it is unidentified (FRIEND actor with a null id, or a FRIEND share with a null `friendId`): "Pick who ‘<name>’ is first".
  - it is a refund whose original is in a different chapter, or in none: "Follows its original purchase".

  Pending transactions **may** be tagged, but they count for nothing until reviewed (R12). Gifts (`ledgerEffect = NONE`) may be tagged and count for nothing, as in the base ledger.
- **R5. Non-members are added automatically on tag.** Every friend in an eligible transaction who isn't a member is added in the same database transaction. The UI then says so: "Added Dan to Goa trip".
- **R6. Refunds follow their original.** Tagging, untagging or moving a purchase does the same to every refund linked to it through `refundsTransactionId`. A refund can't be tagged on its own.
- **R7. A member can be removed only if no tagged transaction involves them.** Otherwise say "Dan is in 3 transactions here".

### Lifecycle
- **R8. States are `OPEN` and `CLOSED`.** Closing freezes the chapter:
  - no tagging or untagging.
  - tagged transactions can't be edited or deleted (the entry screen shows "In Goa trip (closed). Reopen to edit.").
  - members can't change.
  - it **keeps feeding the base ledger**.
  - reopening is allowed. Closing a chapter that isn't settled is allowed, after a warning that lists who still owes.
- **R9. "Settled"** is derived, not stored: every net is 0 and no tagged transaction is pending.
- **R10. At most one chapter is active** (`isActive`), and only an open one. Closing or deleting it clears the flag.
- **R11. Deleting a chapter untags everything in it.** After confirmation, every tagged transaction returns to the base ledger (R15, replay), then the chapter and its rows are deleted. Transactions are never deleted along with a chapter.

### Computation
- **R12. Only reviewed transactions count.** `isPending = false` and `ledgerEffect = DEBT`.
- **R13. Legs for a tagged transaction:**
  - **Direct payment**: no shares with a side, both ends are people (ME or a known friend), and they're different people. It produces exactly one leg: *payee owes payer the amount*. For ME↔friend this is exactly what `applyRepayment` / `applyOutgoingSettlement` do to the net in the base ledger. For friend↔friend it's new, and it's how "Rahul paid Dan back" is recorded.
  - **Otherwise**: `IouLegs.legs(payer, payee, shares, amount, ledgerEffect, IouLegs.resolve(tx, shares))`.
  - **Then drop every leg** whose debtor or creditor isn't `Me` or `Friend(id)`. Shops are spending, and unidentified people can't be in a tagged transaction anyway (R4).
- **R14. Simplify to fewest payments, deterministically.** See section 6. Changing the order of the input must never change the plan.

### Feeding the base ledger
- **R15. Tagged transactions post nothing to `iou_entries`.** Their effect reaches the base ledger only through `chapter_balances`. Whenever a transaction enters or leaves the base ledger (tag, untag, move, chapter delete), **replay the base ledger for the friends involved** (section 7.2). Never just delete the transaction's entries: that breaks the settle-oldest-first bookkeeping.
- **R16. Friend balance = personal balance + Σ contributions.** It's live, including for closed chapters.
- **R17. `chapter_balances` is derived and rebuildable.** Recompute a chapter's rows after any change that could affect it (section 7.1). The rows can always be rebuilt from `transactions` and shares.

### Settle-ups and the active chapter
- **R18. Prompt for chapter settle-ups.** Saving or reviewing an **untagged** direct payment (R13 shape) between two people who are both in an open, unsettled chapter asks "Is this for Goa trip?" before saving. It offers each such chapter, plus "Not for a chapter". The **one-tap notification confirm** (`PendingTransactionReviewer`) must refuse such a transaction (return false) so the user has to open the entry screen.
- **R19. The active chapter is pre-selected** on the entry screen (new transaction or pending review) when:
  - the transaction is eligible (R4), **and**
  - every friend in it is already a member. Don't pre-select into auto-adding people.

  The user can clear it. The one-tap notification confirm must also refuse a transaction the active chapter would pre-select. When a chapter is pre-selected, the prompt in R18 doesn't appear.

### Privacy and sharing
- **R20. A chapter stays on the device unless its owner shares it.** Parcel and mailbox sharing of a tagged transaction work as today, and `chapterId` is never written into a parcel. Parcel import lands transactions untagged (they're pending anyway, and R19 applies when they're reviewed). Sharing a whole chapter with its members is specified in [declarations-design.md](declarations-design.md) (rules S1–S10). It supersedes the v1 rule that chapters never leave the device.

---

## 6. Simplification algorithm

Pure code in `domain/chapter/ChapterMath.kt`.

```kotlin
sealed interface ChapterParty { data object Me; data class Friend(val id: Long) }   // or reuse IouParty.Me / IouParty.Friend

data class ChapterPayment(val debtor: ChapterParty, val creditor: ChapterParty, val amountPaise: Long)

data class ChapterResult(
    val nets: Map<ChapterParty, Long>,              // non-zero only; sums to 0
    val plan: List<ChapterPayment>,
    val contributions: Map<Long, Long>,             // friendId -> +owes ME / -ME owes; from plan only
    val pendingCount: Int,
    val settled: Boolean
)

fun compute(transactions: List<TaggedTx>): ChapterResult   // TaggedTx = Transaction + its shares
```

Steps:
1. Build legs per R12–R13. Also collect **transacted pairs**: the unordered pairs that appear together in any leg.
2. `nets[p] = Σ credit − Σ debit`. Drop zeros. `require(nets.values.sum() == 0L)`.
3. **Exact matches first.** While some debtor's magnitude equals some creditor's, pair them. If there's more than one choice, prefer a transacted pair, then a pair involving ME, then the smaller friend id.
4. **Greedy for the rest.** Take the debtor with the largest remaining amount (ties: ME first, then smaller friend id). Pick a creditor in this order:
   (a) the same remaining amount,
   (b) a pair that transacted,
   (c) the largest remaining,
   (d) ME,
   (e) the smaller friend id.
   Pay `min` of the two and repeat. Each step zeroes at least one party, so there are at most (non-zero parties − 1) payments.
5. `contributions[f] = Σ plan amounts where debtor is Friend(f) and creditor is Me − Σ where debtor is Me and creditor is Friend(f)`.

Optional later: for 12 or fewer non-zero parties, compute the true minimum. That's the count minus the maximum number of disjoint zero-sum subsets, found by subset DP. Greedy doesn't always reach it.

**Invariants to unit test:**
- nets sum to 0.
- the plan preserves every party's net exactly.
- payments ≤ non-zero parties − 1.
- the output is deterministic under input shuffling.
- ME's contributions sum to ME's net.
- pending and `NONE` transactions have no effect.
- a direct friend↔friend payment reduces that debt.
- a tagged transaction with only ME and one friend gives the **same** contribution as `postLedger` would have posted. Check this with a recorder like `ParcelImportRepository.DeltaRecorder`. This is the guarantee that tagging a simple transaction doesn't change any balance.

---

## 7. Keeping the base ledger right

### 7.1 Recomputing `chapter_balances`
`ChapterRepository.recompute(chapterId)`:
1. Load the tagged transactions and their shares.
2. Run `ChapterMath.compute`.
3. In one database transaction, delete that chapter's `chapter_balances` rows and insert the non-zero contributions.

Call it after:
- tag, untag or move (for both the old and the new chapter).
- create, edit or delete of a tagged transaction.
- review of a pending tagged transaction.
- a friend merge that touches its members.

Chapters are small, so recomputing the whole chapter is fine.

### 7.2 Replaying the base ledger when a transaction moves in or out
**Why it's needed**, with an example:
1. On 1 Sep a dinner leaves Rahul owing ₹500. That's an entry of +500.
2. On 5 Sep Rahul pays back ₹500, untagged. The repayment marks the +500 entry settled and writes nothing new, so Rahul's balance is 0.
3. Later the dinner is tagged into a chapter. Just deleting its entries would remove the settled +500, and the repayment's effect would vanish with it. The chapter now says +500, so the friend balance shows +500. That's wrong: he already paid.

**Replay** rebuilds the base ledger for a set of friends from scratch:
1. In one database transaction, delete `iou_entries` for those friends.
2. Take every transaction that is **untagged, reviewed**, and involves any of them, ordered by `dateEpoch ASC, id ASC`.
3. Run `postLedger` for each, through a `LedgerPort` wrapper that forwards only calls for friends in the set to `LedgerManager`.

In the example the repayment then finds nothing to settle and records −500, so the friend balance is −500 + 500 = 0. Correct. R18 is then what gets the repayment tagged into the chapter, so the chapter itself shows settled.

Put this in `ledger/LedgerReplayer.kt`. Keep the ordering and forwarding logic testable with a fake `LedgerPort`.

**Trade-off:** `settledEpoch` on rebuilt entries becomes "now". Consider passing the repayment's `dateEpoch` into `settleEntry` so replays and normal saves agree, but keep that change small.

"Involves" means payer, payee, sided share or iou entry, the same as `getTransactionsForFriendSync`. The friends to replay are every friend in the moved transaction, plus its refunds (R6).

### 7.3 Write-path changes
- `TransactionPersistenceService`: the save request carries `chapterId`.
  - If it's set, delete the transaction's `iou_entries` and **skip** `postLedger`.
  - If `chapterId` changed compared with the stored row (null↔id or id↔id), replay the affected friends (7.2) after the save and recompute the old and new chapters (7.1), in the same database transaction if practical.
  - Enforce R4–R6 and R8 here, not just in the UI.
- `PendingTransactionReviewer.review`: return false (needs the screen) when the transaction is tagged, when R18 would prompt, or when R19 would pre-select. Otherwise behave as today.
- Every delete path for a tagged transaction: recompute its chapter. No replay is needed, since it had nothing in `iou_entries`.
- `SettingsRepository.mergeFriendInto`: move `chapter_members` (if the target is already a member, drop the source row) and recompute affected chapters. `chapter_balances` rows for the source are rebuilt by the recompute.
- `SettingsRepository.friendHasHistory`: chapter membership counts as history.

### 7.4 Read-path changes
- `LedgerRepository.getSummaryForFriend`:
  - net = personal + Σ `chapter_balances` for the friend.
  - add `personalBalancePaise` and `chapterBalances: List<FriendChapterBalance(chapterId, name, state, amountPaise)>` to `FriendLedgerSummary`.
  - for the lifetime totals (`totalTheyOwedYou`, `totalYouOwedThem`), add each current contribution to the matching side.
- `getAllSummaries`: include friends who only have chapter balances. Today it starts from `iouDao().getAllNetBalances()`, which would miss Dan in the example.
- The dashboard picks this up through `getAllSummaries`.

---

## 8. Data model (migration 19 → 20)

```kotlin
enum class ChapterState { OPEN, CLOSED }   // stored as TEXT, like the other enums (see Converters.kt)

@Entity(tableName = "chapters")
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdEpoch: Long,
    val state: ChapterState = ChapterState.OPEN,
    val closedEpoch: Long? = null,
    val isActive: Boolean = false,       // at most one true (R10); set with UPDATE chapters SET isActive = (id = :id)
    val notes: String? = null
)

@Entity(
    tableName = "chapter_members",
    primaryKeys = ["chapterId", "friendId"],
    foreignKeys = [
        ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Friend::class, parentColumns = ["id"], childColumns = ["friendId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("friendId")]
)
data class ChapterMember(val chapterId: Long, val friendId: Long, val addedEpoch: Long)

@Entity(
    tableName = "chapter_balances",
    primaryKeys = ["chapterId", "friendId"],
    foreignKeys = [ /* same two as chapter_members */ ],
    indices = [Index("friendId")]
)
data class ChapterBalance(val chapterId: Long, val friendId: Long, val amountPaise: Long)  // + friend owes ME
```

`Transaction` gets `val chapterId: Long? = null` with `Index("chapterId")`. **Give it no foreign key**:
adding one would mean rebuilding the `transactions` table. The app enforces integrity instead (R11
untags before delete).

The migration, guarded like the existing ones:
- `CREATE TABLE IF NOT EXISTS` for the three tables, plus their indices.
- `ALTER TABLE transactions ADD COLUMN chapterId INTEGER` if the column is missing.
- `CREATE INDEX IF NOT EXISTS index_transactions_chapterId ON transactions(chapterId)`.

Copy the `CREATE` statements from the generated `app/schemas/.../20.json` so Room's schema check
passes. Add a `ChapterDao` for chapter CRUD, members, active flag, balances and a
"tagged transactions for a chapter" query. Also add
`TransactionDao.getTransactionsForFriendSync` filtering (or a sibling query) for untagged rows.

---

## 9. UI

- **Chapters list** (new `ui/chapter/ChaptersActivity`, reached from the dashboard's friends section): name, state (Open, Closed or Settled), member count, the user's net in the chapter, and an "Active" badge. A button creates a chapter.
- **Create or edit a chapter:** name, members (multi-select from friends), notes.
- **Chapter detail** (`ChapterDetailActivity`):
  - **header:** name, state, an active toggle (open only), close or reopen.
  - **"Who pays whom":** the plan, with rows involving the user first and highlighted.
  - **"Balances":** each member's net.
  - **"Transactions":** tagged rows, newest first. Pending rows are flagged. Tapping one opens the entry screen.
  - **"Add transactions":** pick from eligible untagged transactions that involve at least one member, filtered by a date range. Show the R4 reason on greyed rows and the R5 notice after adding.
  - **overflow menu:** rename, members, delete (R11 confirm).
- **Entry screen** (`ui/transactionentry/*`, which uses the `TransactionEntryUiState`, `TransactionEntryAction` and `TransactionEntryEffect` pattern):
  - a **Chapter** field listing open chapters. Pre-filled per R19, with the R4 reason shown inline when the choice isn't allowed. Hidden for transfers.
  - the settle-up prompt (R18) as a dialog on save.
  - a read-only banner for closed chapters (R8).
- **Friend page:**
  - the list shows only **untagged** transactions.
  - the header total is the friend balance (R16), with the personal part shown beneath it.
  - one row per chapter the friend belongs to, e.g. "Goa trip · owes you ₹300", or "· even" when it's 0. The row opens the chapter.
- **Active chapter banner** on the dashboard and the entry screen: "Adding to Goa trip". Tapping it opens the chapter.

---

## 10. Edge cases

| Case | Behaviour |
|---|---|
| A tagged pending transaction | Listed in the chapter as pending and counted in `pendingCount`. It has no effect until reviewed (R12). |
| Editing a tagged transaction's amount or split | Save, then recompute the chapter. No replay. |
| Moving a transaction from chapter A to B | Recompute both. No replay (it was never in the base ledger). |
| Untagging | Replay its friends, then recompute the chapter. |
| Tagging a transaction whose IOU was already repaid outside the chapter | Replay keeps balances right (7.2). R18 and the chapter screen push the repayment into the chapter. |
| A friend in no transaction with ME ends up owing ME through the plan | Intended. They appear on the dashboard and get a friend-page row. |
| The plan reshuffles after a new transaction | Expected: the plan is recomputed live. The tie-break rules in section 6 limit churn. Settle-ups tagged into the chapter (R18) keep totals right however it reshuffles. |
| Friend merge where both are members of one chapter | Keep one membership row and recompute. |
| Deleting a friend who is a member | Blocked, because membership counts as history. |
| Restoring a backup from before chapters | New tables are empty and `chapterId` is null, so everything is untagged, the same as before. |
| Sharing a tagged transaction by parcel or mailbox | Works as today. The chapter isn't included (R20). |

---

## 11. Tests

JVM, `app/src/test/...`:
- `ChapterMathTest`: the invariants in section 6, the worked example (section 4), direct payments ME↔friend and friend↔friend, gifts, pending rows, legs to a shop ignored, shuffle-determinism, and the tie-breaks.
- `ChapterMathBaseEquivalenceTest`: for ME-plus-one-friend shapes (bill split, direct payment either way, IOU left out), the contribution equals what `LedgerPostingService.postLedger` records into a recorder.
- `ChapterEligibilityTest`: every R4 reason, the R5 missing-member list, and the R6 refund rule.
- `LedgerReplayerTest`, with a fake `LedgerPort`: order is `dateEpoch` then id, only the selected friends are forwarded, and tagged and pending transactions are skipped. Plus the section 7.2 example end to end.
- Migration: build and check `20.json`. An instrumented migration test is optional.

---

## 12. Suggested order of work

1. Entities, DAO, migration and `ChapterMath`, with tests.
2. `ChapterEligibility`, `ChapterRepository` (CRUD, tag and untag with auto-add, recompute) and `LedgerReplayer`, with tests.
3. The write-path hooks (section 7.3) and read path (section 7.4). Balances are correct from here on, even before any UI.
4. Chapters list, detail, bulk add, and the chapter field on the entry screen.
5. The settle-up prompt, the notification-confirm guard, and the active chapter.
6. Friend page, dashboard, friend merge and delete, and a check of backup and restore order.

## 13. Future (not v1)
- **Export:** a text summary for chat, and later CSV.
- ~~Sharing a chapter with members~~: done. See [declarations-design.md](declarations-design.md). The owner stays the only editor, linked members hold a live copy that uses the owner's plan, and anyone can be pasted a static copy.
- True minimum payments via subset DP for small groups.
- Per-chapter spend statistics, possibly including the user's solo spending during a trip, which R4 currently excludes.

## 14. Open points to confirm with the user
- Should solo transactions (only the user and a shop) be taggable, so a trip chapter shows total trip spend? R4 currently says no.
- Should closing an unsettled chapter be allowed with a warning (current rule), or blocked?
- Lifetime totals on the friend page (7.4): add the contributions as specified, or show them separately?
