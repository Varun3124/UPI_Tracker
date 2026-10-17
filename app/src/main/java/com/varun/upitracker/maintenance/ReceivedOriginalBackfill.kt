package com.varun.upitracker.maintenance

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.varun.upitracker.data.repository.ReceivedOriginalRepository
import com.varun.upitracker.database.AppDatabase

private const val TAG = "ReceivedOriginalBackfill"

/**
 * Gives every transaction received before originals were kept an original: the row as it stands
 * counts as how it arrived. From then on, changing it shows "Go to original", the same as for a row
 * received since -- see [ReceivedOriginalRepository.captureIfMissingInTransaction].
 *
 * Keyed on the data rather than a preference, like [IouRecoveryBackfill]: a backup restored from
 * before the originals table lands without them, and needs exactly this again. Once every received
 * row has one, it costs a single query.
 */
class ReceivedOriginalBackfill(private val context: Context) {

    suspend fun run() {
        val db = AppDatabase.getInstance(context)
        val rows = db.receivedOriginalDao().getReceivedWithoutOriginal()
        if (rows.isEmpty()) return

        val originals = ReceivedOriginalRepository(db)
        db.withTransaction { rows.forEach { originals.captureIfMissingInTransaction(it) } }
        Log.d(TAG, "Received original backfill complete: captured=${rows.size}")
    }
}
