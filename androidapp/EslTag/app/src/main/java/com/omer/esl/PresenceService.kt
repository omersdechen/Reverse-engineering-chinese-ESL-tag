package com.omer.esl

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import androidx.annotation.RequiresApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Android wakes this when the paired tag starts advertising within range. */
@Suppress("OVERRIDE_DEPRECATION")
class PresenceService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun go(why: String) {
        EslLog.add(this, why)
        scope.launch { Runner.runIfDue(applicationContext) }
    }

    override fun onDeviceAppeared(address: String) = go("tag appeared $address")
    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = go("tag appeared")
    override fun onDeviceDisappeared(address: String) {}
}

/** Fallback in case presence callbacks are suppressed by the OEM battery manager. */
class DailyWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result { Runner.runIfDue(applicationContext); return Result.success() }
}
