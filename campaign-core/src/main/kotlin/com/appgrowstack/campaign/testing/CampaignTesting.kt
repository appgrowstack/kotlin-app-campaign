package com.appgrowstack.campaign.testing

import com.appgrowstack.campaign.CampaignClock
import com.appgrowstack.campaign.CampaignEvent
import com.appgrowstack.campaign.CampaignFetchResult
import com.appgrowstack.campaign.CampaignImagePrefetcher
import com.appgrowstack.campaign.CampaignSetFetcher
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Answers the queued results in order; throws when the queue is empty (offline). */
public class FakeCampaignSetFetcher : CampaignSetFetcher {
    private val queue = ConcurrentLinkedQueue<Result<CampaignFetchResult>>()
    public val etags: MutableList<String?> = CopyOnWriteArrayList()

    public fun enqueueSet(body: String, etag: String? = null) { queue.add(Result.success(CampaignFetchResult.Fetched(body, etag))) }
    public fun enqueueNotModified() { queue.add(Result.success(CampaignFetchResult.NotModified)) }
    public fun enqueueFailure() { queue.add(Result.failure(IllegalStateException("offline"))) }

    override suspend fun fetch(etag: String?): CampaignFetchResult {
        etags.add(etag)
        return (queue.poll() ?: Result.failure(IllegalStateException("offline"))).getOrThrow()
    }
}

/** A clock that moves only when told; `sleep` moves it. */
public class FakeCampaignClock(start: Long) : CampaignClock {
    private val millis = AtomicLong(start)
    override fun now(): Long = millis.get()
    public fun advance(millis: Long) { this.millis.addAndGet(millis) }
    override suspend fun sleep(millis: Long) {
        advance(millis)
        yield()
    }
}

/** Collects what the engine records. */
public class CampaignEventRecorder {
    public val events: MutableList<CampaignEvent> = CopyOnWriteArrayList()
    public val record: (CampaignEvent) -> Unit = { events.add(it) }
    public fun named(name: String): List<CampaignEvent> = events.filter { it.name == name }
}

/** Every image is ready, or none. */
public class FakeImagePrefetcher(private val ready: Boolean = true) : CampaignImagePrefetcher {
    override suspend fun prefetch(url: String): Boolean = ready
}
