package com.varun.upitracker.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.varun.upitracker.database.entity.CategoryKind
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
     * The outflow leg's `(MERCHANT on either side OR ledgerEffect = NONE)` predicate is
     * deliberately the same gate as
     * [com.varun.upitracker.domain.transactionentry.share.ShareCalculator.categoryTargeting]:
     * whatever the entry screen lets you attach expense categories to must be counted here, and
     * nothing else. Change one and change the other, or the dashboard total and the per-category
     * breakdown will disagree.
     *
     * An unlinked merchant credit is income and appears in neither leg -- it only ever puts ME on
     * the PAYEE side, which the outflow leg does not read.
     *
     * Not scoped to an account: a share row's own side says which side ME is on, since ME can be a
     * secondary participant in a split where the primary payer is a FRIEND -- and those carry no
     * `myAccountId` at all, so a per-account query would silently drop them.
     */
    @Query(
        """
        SELECT COALESCE(SUM(paise), 0) FROM (
            SELECT s.amountPaise AS paise
            FROM transaction_shares s
            INNER JOIN transactions t ON t.id = s.transactionId
            WHERE t.dateEpoch > :fromEpochExclusive
              AND t.dateEpoch <= :toEpochInclusive
              AND s.participantType = 'ME'
              AND COALESCE(
                    s.side,
                    CASE WHEN t.payerActorType = 'ME' THEN 'PAYER'
                         WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END
                  ) = 'PAYER'
              AND t.refundsTransactionId IS NULL
              AND (t.payerActorType = 'MERCHANT'
                   OR t.payeeActorType = 'MERCHANT'
                   OR t.ledgerEffect = 'NONE')

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
     * Mirrors [getExpenseTotalBetween]'s outflow leg on the PAYEE side instead of PAYER: ME's share
     * of a transaction where money came from a MERCHANT or a ledger-neutral gift. The same gate
     * excludes a friend settling a debt (`ledgerEffect = DEBT`, no merchant involved) -- getting
     * repaid is not income, it is a receivable turning back into cash.
     *
     * `refundsTransactionId IS NULL` excludes a refund's own credit: unlike a fresh merchant credit,
     * that money is already counted as reduced expense in the ORIGINAL purchase's period (see
     * [getExpenseTotalBetween]'s second leg), so counting it again here would double it.
     *
     * There is no second leg: refunds only ever reverse EXPENSE, never INCOME.
     */
    @Query(
        """
        SELECT COALESCE(SUM(s.amountPaise), 0)
        FROM transaction_shares s
        INNER JOIN transactions t ON t.id = s.transactionId
        WHERE t.dateEpoch > :fromEpochExclusive
          AND t.dateEpoch <= :toEpochInclusive
          AND s.participantType = 'ME'
          AND COALESCE(
                s.side,
                CASE WHEN t.payerActorType = 'ME' THEN 'PAYER'
                     WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END
              ) = 'PAYEE'
          AND t.refundsTransactionId IS NULL
          AND (t.payerActorType = 'MERCHANT'
               OR t.payeeActorType = 'MERCHANT'
               OR t.ledgerEffect = 'NONE')
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

    @Query(
        """
        SELECT DISTINCT t.* FROM transactions t
        LEFT JOIN iou_entries i
            ON t.id = i.transactionId
           AND i.friendId = :friendId
        LEFT JOIN transaction_shares s
            ON t.id = s.transactionId
           AND s.friendId = :friendId
        WHERE t.payerFriendId = :friendId
           OR t.payeeFriendId = :friendId
           OR i.friendId = :friendId
           OR s.friendId = :friendId
        ORDER BY t.dateEpoch DESC, t.id DESC
        """
    )
    suspend fun getTransactionsForFriendSync(friendId: Long): List<com.varun.upitracker.database.entity.Transaction>

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
     * The first leg repeats [getExpenseTotalBetween]'s gate -- merchant or ledger-neutral, with
     * ME's share on the side matching [kind] -- so the two cannot diverge on data shape. Without
     * it, a merchant credit predating this work still carries positive EXPENSE splits and would
     * inflate the breakdown above the total it is supposed to partition.
     *
     * Summing this equals [getExpenseTotalBetween] for the same window, minus any legacy
     * transaction that has shares but no splits -- those pre-date mandatory category selection
     * and are flagged pending for review. It does NOT include transfer spend, which
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
              AND (t.payerActorType = 'MERCHANT'
                   OR t.payeeActorType = 'MERCHANT'
                   OR t.ledgerEffect = 'NONE')
              AND EXISTS (
                  SELECT 1 FROM transaction_shares s
                  WHERE s.transactionId = t.id
                    AND s.participantType = 'ME'
                    AND COALESCE(
                          s.side,
                          CASE WHEN t.payerActorType = 'ME' THEN 'PAYER'
                               WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END
                        ) = CASE WHEN :kind = 'EXPENSE' THEN 'PAYER' ELSE 'PAYEE' END
              )

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
     * The **payee** side is the counterparty: an expense requires ME's share on the payer side, so
     * whoever was paid is on the other one. The joins are LEFT because a ledger-neutral gift has a
     * friend rather than a merchant, and an inner join would silently drop it -- leaving the
     * breakdown short of the slice it is supposed to partition.
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
              AND (t.payerActorType = 'MERCHANT'
                   OR t.payeeActorType = 'MERCHANT'
                   OR t.ledgerEffect = 'NONE')
              AND EXISTS (
                  SELECT 1 FROM transaction_shares s
                  WHERE s.transactionId = t.id
                    AND s.participantType = 'ME'
                    AND COALESCE(
                          s.side,
                          CASE WHEN t.payerActorType = 'ME' THEN 'PAYER'
                               WHEN t.payeeActorType = 'ME' THEN 'PAYEE' END
                        ) = CASE WHEN :kind = 'EXPENSE' THEN 'PAYER' ELSE 'PAYEE' END
              )

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
