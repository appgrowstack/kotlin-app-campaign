package com.appgrowstack.campaign

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

// MARK: storage

/** What the engine keeps between launches, besides the set itself. Times are epoch milliseconds. */
@Serializable
public data class CampaignStoredState(
    /** When the set was last fetched or confirmed unchanged (304). Drives `cache_hours`. */
    val fetchedAt: Long? = null,
    val etag: String? = null,
    val firstSeenAt: Long,
    val history: CampaignHistory = CampaignHistory(),
    /** `campaign|set_version|reason` already sent as `campaign_rejected`, so each is sent once. */
    val reportedRejections: Set<String> = emptySet(),
    /** The set version already reported as `campaigns_stale`. */
    val staleReportedVersion: Int? = null,
    /** The app version of the last launch, for `app_updated`. */
    val lastAppVersion: String? = null,
)

public interface CampaignStorage {
    public suspend fun loadSet(): String?
    public suspend fun saveSet(text: String)
    public suspend fun loadState(): CampaignStoredState?
    public suspend fun saveState(state: CampaignStoredState)
}

/** Files under `<directory>/campaigns/`: `set.json` (the set as fetched) and `state.json`. Pass `context.filesDir`. */
public class FileCampaignStorage(directory: File) : CampaignStorage {
    public val directory: File = File(directory, "campaigns")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadSet(): String? = read("set.json")
    override suspend fun saveSet(text: String): Unit = write("set.json", text)
    override suspend fun loadState(): CampaignStoredState? =
        read("state.json")?.let { runCatching { json.decodeFromString(CampaignStoredState.serializer(), it) }.getOrNull() }
    override suspend fun saveState(state: CampaignStoredState): Unit =
        write("state.json", json.encodeToString(CampaignStoredState.serializer(), state))

    private suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(directory, name).takeIf { it.exists() }?.readText()
    }

    private suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File(directory, "$name.tmp")
        temp.writeText(text)
        if (!temp.renameTo(File(directory, name))) {
            File(directory, name).writeText(text)
            temp.delete()
        }
    }
}

/** Keeps everything in memory (tests and previews). */
public class InMemoryCampaignStorage(
    private var set: String? = null,
    private var state: CampaignStoredState? = null,
) : CampaignStorage {
    private val lock = Mutex()
    override suspend fun loadSet(): String? = lock.withLock { set }
    override suspend fun saveSet(text: String): Unit = lock.withLock { set = text }
    override suspend fun loadState(): CampaignStoredState? = lock.withLock { state }
    override suspend fun saveState(state: CampaignStoredState): Unit = lock.withLock { this.state = state }
}

// MARK: fetching

public sealed interface CampaignFetchResult {
    public data object NotModified : CampaignFetchResult
    public data class Fetched(val body: String, val etag: String?) : CampaignFetchResult
}

public fun interface CampaignSetFetcher {
    /** Throws when the network or the server failed; the engine keeps the set on hand. */
    public suspend fun fetch(etag: String?): CampaignFetchResult
}

/** A fetch that got an answer other than a set or "not modified". */
public class CampaignSetFetchFailure(public val status: Int) : Exception("campaign set: HTTP $status")

/**
 * The set shipped in the app (`app-growth campaigns bundle <project> --out <path>` writes it from
 * the app's `campaigns/`; put it in the app's assets). Works with no server. To change what is
 * shown, ship a new build.
 *
 * @param load reads the JSON, e.g. `{ context.assets.open("campaigns.json").bufferedReader().use { it.readText() } }`.
 *   Read once, on the first fetch.
 */
public class BundledCampaignSetFetcher(private val load: () -> String) : CampaignSetFetcher {
    private val text: String by lazy(load)

    /** `"bundled-<hash of the text>"`: the same file gives the same tag on every launch. */
    public val etag: String get() = "\"bundled-${Integer.toHexString(text.hashCode())}\""

    override suspend fun fetch(etag: String?): CampaignFetchResult =
        if (etag == this.etag) CampaignFetchResult.NotModified else CampaignFetchResult.Fetched(text, this.etag)
}

// MARK: clock

public interface CampaignClock {
    /** Epoch milliseconds. */
    public fun now(): Long
    public suspend fun sleep(millis: Long)
}

public object SystemCampaignClock : CampaignClock {
    override fun now(): Long = System.currentTimeMillis()
    override suspend fun sleep(millis: Long): Unit = delay(millis)
}

// MARK: images

/** Fetches HTTPS images when a set arrives, so a campaign is never shown with its image missing. */
public fun interface CampaignImagePrefetcher {
    /** `true` when the image is ready to show. */
    public suspend fun prefetch(url: String): Boolean

    public companion object {
        /**
         * Only checks that the image answers 200. Pass one that loads into the app's image cache
         * (Coil's `ImageLoader.execute`) so the placement shows it at once.
         */
        public val Http: CampaignImagePrefetcher = CampaignImagePrefetcher { url ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URI(url).toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 30_000
                        connection.readTimeout = 30_000
                        connection.responseCode == 200
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(false)
            }
        }
    }
}
