package com.rakibulcodes.callerinfo

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.TelegramManager
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import kotlinx.coroutines.delay

class OfflineLookupWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val number = inputData.getString("number") ?: return Result.failure()

        val telegramManager = TelegramManager.getInstance(context)
        
        // Wait briefly for telegram manager to connect if it was just instantiated
        var retries = 0
        while (!telegramManager.isReady() && retries < 15) {
            delay(1000)
            retries++
        }
        
        if (!telegramManager.isReady()) {
            return Result.retry() 
        }

        val repository = CallerInfoRepository.getInstance(context)
        val result = repository.getCallerInfo(number)

        if (result.error == "No internet connection") {
            return Result.retry()
        }

        val message = NotificationHelper.buildNotificationMessage(result)
        NotificationHelper.showNotification(context, result.number, message, result = result)

        return Result.success()
    }
}
