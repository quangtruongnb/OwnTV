package tv.own.owntv.features.sports.tvhome

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.SportsRepository
import tv.own.owntv.core.sports.model.Match
import java.util.concurrent.TimeUnit

/**
 * Keeps the home screen's "Sports" channel current: a periodic job every [PERIOD_MINUTES] while
 * Settings → Android TV Home is on, plus a run when the app starts and whenever the profile, its
 * followed leagues/teams or spoiler-free change. Turning Android TV Home off removes the channel.
 */
class SportsChannelSync(
    private val context: Context,
    private val settings: SettingsRepository,
    private val planner: SportsChannelPlanner,
    private val publisher: SportsChannelPublisher,
    private val repository: SportsRepository,
) {
    private val mutex = Mutex()

    /** Watches the switches that matter for as long as the app runs. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!publisher.isSupported) return
        scope.launch {
            combine(
                settings.androidTvHomeEnabled,
                settings.activeProfileId,
                settings.activeSportsConfig.map { Triple(it.leagues, it.teams.map { t -> t.key }, it.spoilerFree) },
            ) { enabled, pid, follow -> Triple(enabled, pid, follow) }
                .distinctUntilChanged()
                .debounce(CHANGE_DEBOUNCE_MS)
                .collect { (enabled, _, _) ->
                    if (enabled) schedule() else cancel()
                    runCatching { refresh() }.onFailure { Log.w(TAG, "refresh failed: ${it.message}") }
                }
        }
    }

    /** Plans and publishes once; clears the channel when Android TV Home is off. */
    suspend fun refresh() = mutex.withLock {
        if (!publisher.isSupported) return@withLock
        if (!settings.androidTvHomeEnabled.first() || settings.activeProfileId.first() < 0) {
            publisher.clear()
            return@withLock
        }
        val config = settings.activeSportsConfig.first()
        val matches = planner.plan(config)
        publisher.publish(matches, spoilerFree = config.spoilerFree)
    }

    /** A home screen card's match: as stored, as last planned, or fetched again. */
    suspend fun resolve(link: SportsMatchLink): Match? =
        repository.board.value.match(link.key) ?: planner.cached(link.key) ?: planner.fetch(link)

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<SportsChannelWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val TAG = "SportsChannel"
        const val WORK_NAME = "sports-tv-home-channel"
        const val PERIOD_MINUTES = 15L
        private const val CHANGE_DEBOUNCE_MS = 2_000L
    }
}

/** The periodic run. Never fails the job: a missed run is caught up by the next one. */
class SportsChannelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        runCatching { get<SportsChannelSync>().refresh() }
            .onFailure { Log.w("SportsChannel", "worker run failed: ${it.message}") }
        return Result.success()
    }
}
