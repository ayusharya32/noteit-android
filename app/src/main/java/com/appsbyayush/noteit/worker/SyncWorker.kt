package com.appsbyayush.noteit.worker

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.appsbyayush.noteit.R
import com.appsbyayush.noteit.repo.NoteRepository
import com.appsbyayush.noteit.utils.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.*

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val repository: NoteRepository
): CoroutineWorker(context, workerParams) {

    companion object {
        const val PERIODIC_REQUEST_NAME = "SYNC_WORKER_PERIODIC_REQUEST"
        const val ONE_TIME_REQUEST_NAME = "SYNC_WORKER_ONE_TIME_REQUEST"

        private const val TAG = "SyncWorkeryy"
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Called")
        initForegroundService()

        return withContext(Dispatchers.IO) {
            try {
                repository.syncNotes()
                repository.syncNoteMediaItems()

                val appSettings = repository.getAppSettings()
                repository.saveAppSettings(appSettings.copy(lastSyncTime = Calendar.getInstance().time))

                repository.clearTrashedNotesOlderThan30days()
                repository.clearTrashedNoteMediaItemsOlderThan30days()
                repository.deleteAllLocalMediaFiles()

                Result.success(workDataOf(Constants.WORK_RESULT to Constants.WORK_RESULT_SUCCESS))

            } catch(e: Exception) {
                Log.d(TAG, "doWork: ${e.message}")
                
                Result.failure(workDataOf(Constants.WORK_RESULT to Constants.WORK_RESULT_FAILURE))
            }
        }
    }

    private suspend fun initForegroundService() {
        setForeground(ForegroundInfo(FOREGROUND_NOTIFICATION_ID, getSyncNotesNotification()))
    }

    private fun getSyncNotesNotification(): Notification {
        return NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_LOW)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_text_note)
            .setContentTitle("Syncing Notes...")
            .build()
    }
}