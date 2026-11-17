package com.example.core.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.EnclaveApplication
import java.util.concurrent.TimeUnit

class EphemeralPurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EnclaveApplication ?: return Result.success()
        app.container.repository.purgeExpiredMessagesNow()
        return Result.success()
    }
}

class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EnclaveApplication ?: return Result.success()
        app.container.repository.flushPendingOutbox()
        return Result.success()
    }
}

object EnclaveWorkScheduler {
    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        val purgeReq = PeriodicWorkRequestBuilder<EphemeralPurgeWorker>(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            "enclave_ephemeral_purge",
            ExistingPeriodicWorkPolicy.KEEP,
            purgeReq
        )
        val outboxReq = PeriodicWorkRequestBuilder<OutboxSyncWorker>(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            "enclave_outbox_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            outboxReq
        )
    }

    fun triggerImmediateSync(context: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "enclave_outbox_immediate",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
