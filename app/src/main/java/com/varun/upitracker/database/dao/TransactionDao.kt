package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.CategoryKind
import com.varun.upitracker.database.entity.IouRecovery
import com.varun.upitracker.database.entity.Transaction

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC")
    fun getAllTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    fun getTransactionsSince(fromEpoch: Long): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE isPending = 1 ORDER BY dateEpoch DESC, id DESC")
    fun getPendingTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE upiRefId = :refId LIMIT 1")
    suspend fun findByRefId(refId: String): Transaction?

    /**
     * Re-import guard for shared parcels. Unscoped, because the origin token already qualifies the
     * sender's transaction id -- see [Transaction.sharedRefId].
     */
    @Query("SELECT * FROM transactions WHERE sharedRefId = :sharedRefId LIMIT 1")
    suspend fun findBySharedRefId(sharedRefId: String): Transaction?

    /**
     * Transactions [friendId] holds a share in that says which side it is on -- the ones a parcel
     * to them can place them in. A sideless legacy share does not count; the flip drops it.
     */
    @Query("SELECT DISTINCT transactionId FROM transaction_shares WHERE friendId = :friendId AND side IS NOT NULL")
    suspend fun getTransactionIdsWithSidedShareFor(friendId: Long): List<Long>

    /**
     * Gives a row its mailbox reference, only if it has none yet -- so two sends racing can never
     * leave one transaction known to friends by two references.
     */
    @Query("UPDATE transactions SET shareRef = :shareRef WHERE id = :id AND shareRef IS NULL")
    suspend fun assignShareRef(id: Long, shareRef: String)

    /**
     * Rows from before [Transaction.iouRecovery] existed, whose IOU entries the old inference posted.
     * See [com.varun.upitracker.maintenance.IouRecoveryBackfill].
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE iouRecovery IS NULL")
    suspend fun countMissingIouRecovery(): Int

    @Query("SELECT * FROM transactions WHERE iouRecovery IS NULL ORDER BY id ASC")
    suspend fun getMissingIouRecovery(): List<Transaction>

    @Query("UPDATE transactions SET iouRecovery = :iouRecovery WHERE id = :id")
    suspend fun setIouRecovery(id: Long, iouRecovery: IouRecovery)

    /**
     * Existing rows a received parcel row could already be: [findMatchCandidates]'s rule, widened to
     * rows where ME is only in the split. That is the shape a dinner someone else paid for arrives
     * in -- and, since anyone in a split can now be sent it, the shape in which the same dinner can
     * arrive twice from two different friends.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.amountPaise = :amountPaise
          AND t.dateEpoch BETWEEN :fromEpoch AND :toEpoch
          AND (t.payerActorType = 'ME'
               OR t.payeeActorType = 'ME'
               OR EXISTS (
                   SELECT 1 FROM transaction_shares s
                   WHERE s.transactionId = t.id AND s.participantType = 'ME'
               ))
          AND (t.myAccountId IS NULL OR t.myAccountId IN (:savingsAccountIds))
        ORDER BY t.dateEpoch ASC, t.id ASC
        """
    )
    suspend fun findParcelMatchCandidates(
        amountPaise: Long,
        fromEpoch: Long,
        toEpoch: Long,
        savingsAccountIds: List<String>
    ): List<Transaction>

    /**
     * Re-import guard for bank-statement rows that carry no UPI ref id. Scoped to the account
     * because [Transaction.statementRefNo] is intentionally not globally unique.
     */
    @Query("SELECT * FROM transactions WHERE statementRefNo = :refNo AND myAccountId = :accountId LIMIT 1")
    suspend fun findByStatementRefNo(refNo: String, accountId: String): Transaction?

    /**
     * Existing transactions a bank-statement row could plausibly already be recorded as: I am on
     * one side of it, it sits on one of my savings accounts (or none yet), the amount is exact and
     * the date is within the caller's tolerance window.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amountPaise
          AND dateEpoch BETWEEN :fromEpoch AND :toEpoch
          AND (payerActorType = 'ME' OR payeeActorType = 'ME')
          AND (myAccountId IS NULL OR myAccountId IN (:savingsAccountIds))
        ORDER BY dateEpoch ASC, id ASC
        """
    )
    suspend fun findMatchCandidates(
        amountPaise: Long,
        fromEpoch: Long,
        toEpoch: Long,
        savingsAccountIds: List<String>
    ): List<Transaction>

    /**
     * ME's net expense over `(fromEpochExclusive, toEpochInclusive]`.
     *
     * Two legs. The outflow leg is dated by the transaction; the refund leg is dated by the
     * ORIGINAL purchase, so a refund reduces the period the money was actually spent in.
     *
     * The outflow leg reads EXPENSE category splits, because a split is exactly what
     * [com.varun.upitracker.domain.transactionentry.share.ShareCalculator.categoryTargeting] made the
     * entry screen ask for: what a transaction left ME down once the IOUs it records are counted.
     * ME's share row is not that figure -- a friend's IOU left out makes their part ME's spending
     * too. Whatever the entry screen categorises is counted here, and nothing else, so the dashboard
     * total and the per-category breakdown agree.
     *
     * The shape gate drops splits whose kind the entry screen could never have offered for that
     * shape: money coming from a shop, or arriving to ME, is income. That is what keeps an old
     * merchant credit's leftover EXPENSE splits out.
     *
     * Not scoped to an account: ME can be in the split of a transaction a FRIEND paid, and those
     * carry no `myAccountId` at all, so a per-account query would silently drop them.
     */
    @Query(
        """
        SELECT COALESCE(SUM(paise), 0) FROM (
            SELECT cs.myAmountPaise AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions t ON t.id = cs.transactionId
            INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = 'EXPENSE'
            WHERE t.dateEpoch > :fromEpochExclusive
              AND t.dateEpoch <= :toEpochInclusive
              AND t.refundsTransactionId IS NULL
              AND NOT (t.payerActorType = 'MERCHANT' OR t.payeeActorType = 'ME')

            UNION ALL

            SELECT -cs.myAmountPaise AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions r ON r.id = cs.transactionId
            INNER JOIN transactions o ON o.id = r.refundsTransactionId
            WHERE o.dateEpoch > :fromEpochExclusive
              AND o.dateEpoch <= :toEpochInclusive
        )
        """
    )
    suspend fun getExpenseTotalBetween(
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long

    /**
     * ME's income over `(fromEpochExclusive, toEpochInclusive]`.
     *
     * Mirrors [getExpenseTotalBetween]'s outflow leg with INCOME splits: what transactions left ME up.
     * A friend repaying a debt recorded as an IOU leaves ME neither up nor down and carries no split
     * -- getting repaid is not income, it is a receivable turning back into cash.
     *
     * `refundsTransactionId IS NULL` excludes a refund's own credit: unlike a fresh merchant credit,
     * that money is already counted as reduced expense in the ORIGINAL purchase's period (see
     * [getExpenseTotalBetween]'s second leg), so counting it again here would double it.
     *
     * There is no second leg: refunds only ever reverse EXPENSE, never INCOME.
     */
    @Query(
        """
        SELECT COALESCE(SUM(cs.myAmountPaise), 0)
        FROM transaction_category_splits cs
        INNER JOIN transactions t ON t.id = cs.transactionId
        INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = 'INCOME'
        WHERE t.dateEpoch > :fromEpochExclusive
          AND t.dateEpoch <= :toEpochInclusive
          AND t.refundsTransactionId IS NULL
          AND NOT (t.payeeActorType = 'MERCHANT' OR t.payerActorType = 'ME')
        """
    )
    suspend fun getIncomeTotalBetween(
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long

    /**
     * How much [accountId]'s balance moved over `(fromEpochExclusive, toEpochInclusive]`.
     *
     * Unlike [getExpenseTotalBetween], which measures spend, this counts the whole amount
     * of every transaction on the account whatever the counterparty, and does not join
     * `transaction_shares` — so pending rows from SMS and statement import count too.
     *
     * Mirrors [com.varun.upitracker.domain.BalanceDeltaCalculator.transactionDelta]; keep the two
     * in step. Covered by `Index(["myAccountId", "dateEpoch"])`.
     */
    @Query(
        """
        SELECT SUM(
            CASE
                WHEN payerActorType = 'ME' AND payeeActorType = 'ME' THEN 0
                WHEN payerActorType = 'ME' THEN -amountPaise
                WHEN payeeActorType = 'ME' THEN amountPaise
                ELSE 0
            END
        )
        FROM transactions
        WHERE myAccountId = :accountId
          AND dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
        """
    )
    suspend fun getAccountBalanceDeltaBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): Long?

    /**
     * What moved in and out of [accountIds] over `(fromEpochExclusive, toEpochInclusive]`.
     *
     * The **same** CASE arms as [getAccountBalanceDeltaBetween], split in two rather than netted,
     * so `inPaise - outPaise` is exactly the figure that query returns for the same window and the
     * same accounts. That is the property the trends charts rest on: the in-and-out card sits
     * directly under the balance line, and the two have to reconcile or the card contradicts the
     * chart above it.
     *
     * Rows, not shares or category splits. Those are only ever written by the entry screen, so an
     * SMS-imported credit carries neither and would count as nothing at all -- which is what left
     * the income bars empty. Money that arrived is money that arrived, reviewed or not.
     *
     * ME on both sides nets to zero, matching the balance query: it moved nothing.
     *
     * Transfers between own accounts live in `account_transfer` and never reach this, so moving
     * money between two of your own accounts is not counted as either direction.
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(
                CASE WHEN payeeActorType = 'ME' AND payerActorType != 'ME' THEN amountPaise ELSE 0 END
            ), 0) AS inPaise,
            COALESCE(SUM(
                CASE WHEN payerActorType = 'ME' AND payeeActorType != 'ME' THEN amountPaise ELSE 0 END
            ), 0) AS outPaise
        FROM transactions
        WHERE myAccountId IN (:accountIds)
          AND dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
        """
    )
    suspend fun getFlowBetween(
        accountIds: List<String>,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): com.varun.upitracker.database.model.FlowTotal


    @Query(
        """
        SELECT * FROM transactions
        WHERE (
            payerFriendId = :friendId
            OR payeeFriendId = :friendId
            OR payerMerchantId = :merchantId
            OR payeeMerchantId = :merchantId
        )
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    fun getTransactionsForEntity(friendId: Long?, merchantId: Long?): LiveData<List<com.varun.upitracker.database.entity.Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): com.varun.upitracker.database.entity.Transaction?

    @Query("SELECT * FROM transactions ORDER BY dateEpoch DESC, id DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE dateEpoch >= :fromEpoch ORDER BY dateEpoch DESC, id DESC")
    suspend fun getTransactionsSinceSync(fromEpoch: Long): List<Transaction>

    @Query(
        """
        SELECT * FROM transactions
        WHERE dateEpoch >= :fromEpoch
          AND dateEpoch < :toEpoch
        ORDER BY dateEpoch DESC, id DESC
        """
    )
    suspend fun getTransactionsBetweenSync(fromEpoch: Long, toEpoch: Long): List<Transaction>

    /**
     * The oldest transaction touching any scoped account, or null if there is none.
     *
     * Bounds how far the trends charts can be panned back. Rows with no account move no balance and
     * are excluded for free -- `NULL IN (...)` is never true.
     */
    @Query("SELECT MIN(dateEpoch) FROM transactions WHERE myAccountId IN (:accountIds)")
    suspend fun getEarliestDateEpochForAccounts(accountIds: List<String>): Long?

    /**
     * The oldest transaction on record, whatever account it sits on, or null on an empty database.
     *
     * What "all time" actually begins at, which is how the statistics screen divides an all-time
     * total into a per-day rate. Deliberately unscoped, unlike [getEarliestDateEpochForAccounts]:
     * the category totals it divides are not scoped to an account either, so a row with no account
     * recorded is counted in the total and has to be counted in the span.
     */
    @Query("SELECT MIN(dateEpoch) FROM transactions")
    suspend fun getEarliestDateEpoch(): Long?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        SELECT * FROM transactions
        WHERE myAccountId = :accountId
          AND dateEpoch > :fromEpochExclusive
          AND dateEpoch <= :toEpochInclusive
        ORDER BY dateEpoch ASC, id ASC
        """
    )
    suspend fun getAccountTransactionsBetween(
        accountId: String,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<com.varun.upitracker.database.entity.Transaction>

    // --- chapters -----------------------------------------------------------------------------

    @Query("UPDATE transactions SET chapterId = :chapterId WHERE id = :transactionId")
    suspend fun setChapter(transactionId: Long, chapterId: Long?)

    /** R6: a refund goes wherever the purchase it reverses goes, and never moves on its own. */
    @Query("UPDATE transactions SET chapterId = :chapterId WHERE refundsTransactionId = :originalId")
    suspend fun setChapterForRefundsOf(originalId: Long, chapterId: Long?)

    /** R11: deleting a chapter returns every transaction in it to the base ledger. */
    @Query("UPDATE transactions SET chapterId = NULL WHERE chapterId = :chapterId")
    suspend fun untagChapter(chapterId: Long)

    /**
     * Everything the base ledger should hold for these friends, oldest first.
     *
     * Tagged rows are excluded because their effect reaches a balance through `chapter_balances`
     * instead (R15). "Involves" means either end, a sided share, or an existing entry -- the last of
     * which catches a friend whose only trace is an entry an edit has since orphaned.
     * [getUntaggedTransactionsForFriendSync] asks the same question for the friend page.
     *
     * The `isPending = 0 OR EXISTS(...)` is load-bearing, not caution. CategorySplitBackfill and
     * MerchantCreditReviewBackfill both flip already-reviewed rows back to pending *without*
     * clearing their entries, and a restore re-arms both -- so a plain `isPending = 0` would delete
     * those entries here and never post them again.
     *
     * [afterEpoch] skips rows every one of these friends has a checkpoint covering: they post nothing
     * for any of them, so there is no point reading them. `Long.MIN_VALUE` reads everything.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.chapterId IS NULL
          AND t.dateEpoch > :afterEpoch
          AND (
            t.isPending = 0
            OR EXISTS (SELECT 1 FROM iou_entries e
                        WHERE e.transactionId = t.id AND e.friendId IN (:friendIds))
          )
          AND (
            t.payerFriendId IN (:friendIds)
            OR t.payeeFriendId IN (:friendIds)
            OR EXISTS (SELECT 1 FROM transaction_shares s
                        WHERE s.transactionId = t.id AND s.side IS NOT NULL
                          AND s.friendId IN (:friendIds))
            OR EXISTS (SELECT 1 FROM iou_entries e2
                        WHERE e2.transactionId = t.id AND e2.friendId IN (:friendIds))
          )
        ORDER BY t.dateEpoch ASC, t.id ASC
        """
    )
    suspend fun getUntaggedPostedForFriends(
        friendIds: List<Long>,
        afterEpoch: Long
    ): List<com.varun.upitracker.database.entity.Transaction>

    /**
     * Rows naming [friendId], outside every chapter and still awaiting review, dated at or before
     * [atOrBeforeEpoch]. Once a checkpoint covers them, reviewing them moves nothing -- which is worth
     * warning about before the checkpoint is agreed (docs/declarations-design.md D13).
     */
    @Query(
        """
        SELECT COUNT(DISTINCT t.id) FROM transactions t
        LEFT JOIN transaction_shares s
            ON s.transactionId = t.id AND s.friendId = :friendId AND s.side IS NOT NULL
        WHERE t.isPending = 1
          AND t.chapterId IS NULL
          AND t.dateEpoch <= :atOrBeforeEpoch
          AND (t.payerFriendId = :friendId OR t.payeeFriendId = :friendId OR s.friendId = :friendId)
        """
    )
    suspend fun countPendingForFriendUntil(friendId: Long, atOrBeforeEpoch: Long): Int

    /** The friend page lists only what the base ledger still holds; the rest lives in its chapter. */
    @Query(
        """
        SELECT DISTINCT t.* FROM transactions t
        LEFT JOIN iou_entries i
            ON t.id = i.transactionId
           AND i.friendId = :friendId
        LEFT JOIN transaction_shares s
            ON t.id = s.transactionId
           AND s.friendId = :friendId
        WHERE t.chapterId IS NULL
          AND (t.payerFriendId = :friendId
           OR t.payeeFriendId = :friendId
           OR i.friendId = :friendId
           OR s.friendId = :friendId)
        ORDER BY t.dateEpoch DESC, t.id DESC
        """
    )
    suspend fun getUntaggedTransactionsForFriendSync(friendId: Long): List<com.varun.upitracker.database.entity.Transaction>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerFriendId = :friendId
           OR payeeFriendId = :friendId
        """
    )
    suspend fun countReferencesForFriend(friendId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE payerMerchantId = :merchantId
           OR payeeMerchantId = :merchantId
        """
    )
    suspend fun countReferencesForMerchant(merchantId: Long): Int

    @Query("UPDATE transactions SET payerFriendId = :targetId WHERE payerFriendId = :sourceId")
    suspend fun reassignPayerFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeFriendId = :targetId WHERE payeeFriendId = :sourceId")
    suspend fun reassignPayeeFriend(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payerMerchantId = :targetId WHERE payerMerchantId = :sourceId")
    suspend fun reassignPayerMerchant(sourceId: Long, targetId: Long)

    @Query("UPDATE transactions SET payeeMerchantId = :targetId WHERE payeeMerchantId = :sourceId")
    suspend fun reassignPayeeMerchant(sourceId: Long, targetId: Long)

    /**
     * Purchases this refund could be reversing: from the same merchant, settled, categorised,
     * not themselves a refund, and not this transaction.
     *
     * Scoped to [merchantId] because a refund comes back from whoever you bought from -- listing
     * every past purchase would make the picker unusable and invite mislinking.
     *
     * Refunds of refunds are excluded so a chain can never form -- the aggregates resolve a
     * refund's period by following exactly one hop to its original.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.isPending = 0
          AND t.refundsTransactionId IS NULL
          AND t.id != :excludeTransactionId
          AND t.dateEpoch <= :refundDateEpoch
          AND (t.payeeMerchantId = :merchantId OR t.payerMerchantId = :merchantId)
          AND EXISTS (SELECT 1 FROM transaction_category_splits cs WHERE cs.transactionId = t.id)
        ORDER BY t.dateEpoch DESC, t.id DESC
        LIMIT :limit
        """
    )
    suspend fun getRefundCandidates(
        merchantId: Long,
        refundDateEpoch: Long,
        excludeTransactionId: Long,
        limit: Int
    ): List<Transaction>

    /**
     * How much has already been refunded against [originalId], per category, ignoring
     * [excludeTransactionId] (the refund currently being edited).
     *
     * Both directions of the over-refund check read this: a new refund must not push a category
     * below zero, and editing the original must not drop a category below what it has already
     * refunded.
     */
    @Query(
        """
        SELECT cs.categoryId AS categoryId, SUM(cs.myAmountPaise) AS amountPaise
        FROM transaction_category_splits cs
        INNER JOIN transactions r ON r.id = cs.transactionId
        WHERE r.refundsTransactionId = :originalId
          AND r.id != :excludeTransactionId
        GROUP BY cs.categoryId
        """
    )
    suspend fun getRefundedAmountsForOriginal(
        originalId: Long,
        excludeTransactionId: Long
    ): List<com.varun.upitracker.database.model.CategoryAmount>

    /** Ids of refunds pointing at [originalId]; used to guard edits and deletes of the original. */
    @Query("SELECT id FROM transactions WHERE refundsTransactionId = :originalId")
    suspend fun getRefundIdsForOriginal(originalId: Long): List<Long>

    /**
     * Net total per category of [kind] over `(fromEpochExclusive, toEpochInclusive]` -- the
     * breakdown behind the statistics page.
     *
     * Same two legs as [getExpenseTotalBetween], grouped by category: splits dated by their own
     * transaction, minus linked-refund splits dated by the ORIGINAL purchase. Both legs are
     * filtered to [kind], so a gift received cannot appear as a positive slice in an expense
     * breakdown -- it writes a split against an INCOME category, and without the filter that
     * income would land in the expense pie.
     *
     * The refund leg contributes nothing when [kind] is INCOME: a refund's pills are narrowed to
     * the purchase's categories, which are always expense.
     *
     * The first leg repeats [getExpenseTotalBetween]'s shape gate for [kind], so the two cannot
     * diverge on data shape. Without it, a merchant credit predating this work still carries positive
     * EXPENSE splits and would inflate the breakdown above the total it is supposed to partition.
     *
     * Summing this equals [getExpenseTotalBetween] (or [getIncomeTotalBetween]) for the same window.
     * It does NOT include transfer spend, which
     * [com.varun.upitracker.data.repository.AccountRepository.getSpendSince] adds separately.
     *
     * Categories whose net is zero are omitted: a fully refunded purchase should not draw an empty
     * slice. A net below zero is impossible while
     * [com.varun.upitracker.domain.transactionentry.validation.TransactionValidator.validateRefundCoverage]
     * holds, since it caps refunds per category against the purchase they reverse.
     */
    @Query(
        """
        SELECT categoryId, categoryName, SUM(paise) AS netPaise FROM (
            SELECT cs.categoryId    AS categoryId,
                   c.name           AS categoryName,
                   cs.myAmountPaise AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions t ON t.id = cs.transactionId
            INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = :kind
            WHERE t.refundsTransactionId IS NULL
              AND t.dateEpoch > :fromEpochExclusive
              AND t.dateEpoch <= :toEpochInclusive
              AND NOT (CASE WHEN :kind = 'EXPENSE'
                            THEN t.payerActorType = 'MERCHANT' OR t.payeeActorType = 'ME'
                            ELSE t.payeeActorType = 'MERCHANT' OR t.payerActorType = 'ME' END)

            UNION ALL

            SELECT cs.categoryId     AS categoryId,
                   c.name            AS categoryName,
                   -cs.myAmountPaise AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions r ON r.id = cs.transactionId
            INNER JOIN transactions o ON o.id = r.refundsTransactionId
            INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = :kind
            WHERE o.dateEpoch > :fromEpochExclusive
              AND o.dateEpoch <= :toEpochInclusive
        )
        GROUP BY categoryId, categoryName
        HAVING SUM(paise) != 0
        ORDER BY netPaise DESC
        """
    )
    suspend fun getTotalsByCategoryBetween(
        kind: CategoryKind,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<com.varun.upitracker.database.model.CategoryTotal>

    /**
     * Net spend per counterparty within a single category over
     * `(fromEpochExclusive, toEpochInclusive]` -- the breakdown behind a drilled-into pie slice.
     *
     * Mirrors [getTotalsByCategoryBetween] leg for leg with a category filter added, so summing
     * this reproduces that category's own `netPaise` exactly.
     *
     * The **payee** side is the counterparty: whoever was paid. The joins are LEFT because a gift or an
     * IOU left out has a friend rather than a merchant there, and an inner join would silently drop it
     * -- leaving the breakdown short of the slice it is supposed to partition.
     *
     * The refund leg groups by the **original purchase's** payee, not the refund's. A refund has
     * the merchant on its payer side, and it is the original's share of the slice being reduced.
     */
    @Query(
        """
        SELECT merchantId, friendId, payeeName, SUM(paise) AS netPaise FROM (
            SELECT t.payeeMerchantId AS merchantId,
                   t.payeeFriendId   AS friendId,
                   COALESCE(m.name, f.name, t.payeeRawLabel, 'Unknown') AS payeeName,
                   cs.myAmountPaise  AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions t ON t.id = cs.transactionId
            INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = :kind
            LEFT JOIN merchants m ON m.id = t.payeeMerchantId
            LEFT JOIN friends f ON f.id = t.payeeFriendId
            WHERE cs.categoryId = :categoryId
              AND t.refundsTransactionId IS NULL
              AND t.dateEpoch > :fromEpochExclusive
              AND t.dateEpoch <= :toEpochInclusive
              AND NOT (CASE WHEN :kind = 'EXPENSE'
                            THEN t.payerActorType = 'MERCHANT' OR t.payeeActorType = 'ME'
                            ELSE t.payeeActorType = 'MERCHANT' OR t.payerActorType = 'ME' END)

            UNION ALL

            SELECT o.payeeMerchantId AS merchantId,
                   o.payeeFriendId   AS friendId,
                   COALESCE(m.name, f.name, o.payeeRawLabel, 'Unknown') AS payeeName,
                   -cs.myAmountPaise AS paise
            FROM transaction_category_splits cs
            INNER JOIN transactions r ON r.id = cs.transactionId
            INNER JOIN transactions o ON o.id = r.refundsTransactionId
            INNER JOIN categories c ON c.id = cs.categoryId AND c.kind = :kind
            LEFT JOIN merchants m ON m.id = o.payeeMerchantId
            LEFT JOIN friends f ON f.id = o.payeeFriendId
            WHERE cs.categoryId = :categoryId
              AND o.dateEpoch > :fromEpochExclusive
              AND o.dateEpoch <= :toEpochInclusive
        )
        GROUP BY merchantId, friendId, payeeName
        HAVING SUM(paise) != 0
        ORDER BY netPaise DESC
        """
    )
    suspend fun getPayeeTotalsForCategoryBetween(
        kind: CategoryKind,
        categoryId: Long,
        fromEpochExclusive: Long,
        toEpochInclusive: Long
    ): List<com.varun.upitracker.database.model.PayeeTotal>
}
