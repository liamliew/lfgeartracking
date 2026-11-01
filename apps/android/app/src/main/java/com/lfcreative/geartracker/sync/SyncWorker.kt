package com.lfcreative.geartracker.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.AuthState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val auth: AuthRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Nothing to send as, until someone is signed in; queued events wait on the device.
        val state = auth.state.first { it !is AuthState.Loading }
        if (state !is AuthState.SignedIn) return Result.success()
        return when (engine.run()) {
            is SyncOutcome.Done -> Result.success()
            is SyncOutcome.Retry -> Result.retry() // exponential backoff
        }
    }
}
