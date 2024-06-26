/*
 * Copyright ${inceptionYear} - ${year} ${owner}
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore

import jetbrains.exodus.ExodusException
import jetbrains.exodus.core.dataStructures.ConcurrentObjectCache
import jetbrains.exodus.core.dataStructures.Priority
import jetbrains.exodus.core.execution.Job
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.entitystore.EntityIterableCache.TooLongEntityIterableInstantiationReason.CACHE_ADAPTER_OBSOLETE
import jetbrains.exodus.entitystore.EntityIterableCache.TooLongEntityIterableInstantiationReason.JOB_OVERDUE
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.env.ReadonlyTransactionException
import mu.KLogging
import java.lang.Long.max

class EntityIterableCache internal constructor(private val store: PersistentEntityStoreImpl) {

    companion object : KLogging() {

        fun toString(config: PersistentEntityStoreConfig, handle: EntityIterableHandle): String {
            return if (config.entityIterableCacheUseHumanReadable) {
                EntityIterableBase.getHumanReadablePresentation(handle)
            } else {
                handle.toString()
            }
        }
    }

    private val config = store.config

    private var deferredIterablesCache =
        ConcurrentObjectCache<Any, Long>(config.entityIterableCacheDeferredSize)

    private var iterableCountsCache =
        ConcurrentObjectCache<Any, Pair<Long, Long>>(config.entityIterableCacheCountsCacheSize)

    private val heavyIterablesCache =
        ConcurrentObjectCache<Any, Long>(config.entityIterableCacheHeavyIterablesCacheSize)

    private var cacheAdapter = EntityIterableCacheAdapter.create(config)

    val stats = EntityIterableCacheStatistics()

    val processor = EntityStoreSharedAsyncProcessor(
        "EntityIterableCacheProcessor",
        config.entityIterableCacheThreadCount
    ).apply {
        // It will ensure that job is not put into tail of the queue when re-queued.
        this.shouldSkipIfPresent(true)
    }
    val countsProcessor = EntityStoreSharedAsyncProcessor(
        "EntityIterableCacheCountsProcessor",
        config.entityIterableCacheCountsThreadCount
    ).apply { this.shouldSkipIfPresent(true) }

    init {
        processor.start()
        countsProcessor.start()
    }

    val isDispatcherThread: Boolean get() = processor.isDispatcherThread || countsProcessor.isDispatcherThread

    // the value is updated by PersistentEntityStoreSettingsListener
    var cachingDisabled = config.isCachingDisabled

    fun getCacheAdapter(): Any {
        return cacheAdapter
    }

    fun count(): Int {
        return cacheAdapter.count().toInt()
    }

    // Exposed for backward compatibility with clients of v3.0
    fun hitRate() = stats.hitRate

    fun clear() {
        cacheAdapter.clear()
        deferredIterablesCache = ConcurrentObjectCache(config.entityIterableCacheDeferredSize)
        iterableCountsCache = ConcurrentObjectCache(config.entityIterableCacheCountsCacheSize)
    }

    /**
     * @param it iterable.
     * @return iterable which is cached or "it" itself if it's not cached.
     */
    fun putIfNotCached(it: EntityIterableBase): EntityIterableBase {
        if (cachingDisabled || !it.canBeCached()) {
            return it
        }
        val handle = it.handle
        val txn = it.transaction
        val localCache = txn.localCache
        txn.localCacheAttempt()
        val cached: EntityIterableBase? = localCache.getObject(handle)
        if (cached != null) {
            if (!cached.handle.isExpired) {
                txn.localCacheHit()
                stats.incTotalHits()
                return cached
            }
            localCache.remove(handle)
        }
        stats.incTotalMisses()
        if (txn.isMutable || !txn.isCurrent || !txn.isCachingRelevant) {
            return it
        }

        // if cache is enough full, then cache iterables after they live some time in deferred cache
        if (config.entityIterableCacheDeferredEnabled && localCache.halfFull) {
            val currentMillis = System.currentTimeMillis()
            val handleIdentity = handle.identity
            val whenCached = deferredIterablesCache.tryKey(handleIdentity)
            if (whenCached == null) {
                deferredIterablesCache.cacheObject(handleIdentity, currentMillis)
                return it
            }
            if (whenCached + config.entityIterableCacheDeferredDelay > currentMillis) {
                return it
            }
        }

        // if we are already within caching dispatcher,
        // then instantiate iterable without queueing a job.
        if (isDispatcherThread) {
            return it.getOrCreateCachedInstance(txn)
        }
        EntityIterableAsyncInstantiation(handle, it, true, processor)
        return it
    }

    fun getCachedCount(handle: EntityIterableHandle): Long? {
        val identity = handle.identity
        iterableCountsCache.tryKey(identity)?.let { (count, time) ->
            // the greater is count, the longer it can live in the cache
            if (System.currentTimeMillis() - time <= max(config.entityIterableCacheCountsLifeTime, count)) {
                stats.incTotalCountHits()
                return count
            }
            // count is expired
            iterableCountsCache.remove(identity)
        }
        stats.incTotalCountMisses()
        return null
    }

    fun getCachedCount(it: EntityIterableBase): Long {
        val handle = it.handle
        val result = getCachedCount(handle)
        if (result == null && isDispatcherThread) {
            return it.getOrCreateCachedInstance(it.transaction).size()
        }
        if (it.isThreadSafe) {
            EntityIterableAsyncInstantiation(handle, it, false, countsProcessor)
        }
        return result ?: -1
    }

    fun setCachedCount(handle: EntityIterableHandle, count: Long) {
        iterableCountsCache.cacheObject(handle.identity, count to System.currentTimeMillis())
    }

    fun compareAndSetCacheAdapter(old: Any, new: Any): Boolean {
        if (cacheAdapter === old) {
            cacheAdapter = new as EntityIterableCacheAdapter
            return true
        }
        return false
    }

    private inner class EntityIterableAsyncInstantiation(
        private val handle: EntityIterableHandle,
        private val it: EntityIterableBase,
        private val isConsistent: Boolean,
        processor: JobProcessor
    ) : Job() {

        private val cancellingPolicy = CachingCancellingPolicy(isConsistent && handle.isConsistent)

        private var currentAttempt: Int = 1

        init {
            this.processor = processor
            if (queue(Priority.normal)) {
                if (isConsistent) {
                    stats.incTotalJobsEnqueued()
                } else {
                    stats.incTotalCountJobsEnqueued()
                }
            } else {
                stats.incTotalJobsNonQueued()
            }
        }

        override fun getName() = "Caching job for handle ${it.handle}"

        override fun getGroup() = store.location

        override fun isEqualTo(job: Job) = handle == (job as EntityIterableAsyncInstantiation).handle

        override fun hashCode(): Int {
            return handle.hashCode()
        }

        override fun execute() {
            // Update cache size lazily
            updateCacheSizeIfNecessary()

            val started = System.currentTimeMillis()
            // don't try to cache if it is too late
            if (!cancellingPolicy.canStartAt(started)) {
                stats.incTotalJobsNotStarted()
                return
            }
            val isConsistent = cancellingPolicy.isConsistent
            val iterableIdentity = handle.identity
            // for consistent jobs, don't try to cache if we know that this iterable was "heavy" during its life span
            if (isConsistent && config.entityIterableCacheHeavyEnabled) {
                val lastCancelled = heavyIterablesCache.tryKey(iterableIdentity)
                if (lastCancelled != null) {
                    if (lastCancelled + config.entityIterableCacheHeavyIterablesLifeSpan > started) {
                        stats.incTotalJobsNotStarted()
                        logger.debug { "Heavy iterable not started, handle=${toString(config, handle)}" }
                        return
                    }
                    heavyIterablesCache.remove(iterableIdentity)
                }
            }

            stats.incTotalJobsStarted()
            store.executeInReadonlyTransaction { txn ->
                if (!handle.isConsistent) {
                    handle.resetBirthTime()
                }
                txn as PersistentStoreTransaction
                cancellingPolicy.setLocalCache(txn.localCache)
                txn.queryCancellingPolicy = cancellingPolicy
                try {
                    it.getOrCreateCachedInstance(txn, !isConsistent)
                    if (logger.isInfoEnabled) {
                        val cachedIn = System.currentTimeMillis() - started
                        if (cachedIn > 1000) {
                            logger.info {
                                val action = if (isConsistent) "Cached" else "Cached (inconsistent)"
                                "$action in $cachedIn ms, handle=${toString(config, handle)}"
                            }
                        }
                    }
                } catch (_: ReadonlyTransactionException) {
                    // work around XD-626
                    val action = if (isConsistent) "Caching" else "Caching (inconsistent)"
                    logger.error("$action failed with ReadonlyTransactionException. Re-queueing...")
                    queue(Priority.below_normal)
                } catch (e: TooLongEntityIterableInstantiationException) {
                    val cachingTime = System.currentTimeMillis() - started

                    if (e.reason == CACHE_ADAPTER_OBSOLETE) {
                        val maxRetries = config.entityIterableCacheObsoleteMaxRetries
                        if (maxRetries > 0 && currentAttempt <= maxRetries) {
                            val handle = toString(config, handle)
                            logger.info { "Re-queuing obsolete cache job for handle ${handle}, retries left: ${maxRetries - currentAttempt}" }
                            currentAttempt++
                            queue(Priority.normal)
                            if (isConsistent) {
                                stats.incTotalJobsRetried()
                            } else {
                                stats.incTotalCountJobsRetried()
                            }

                            return@executeInReadonlyTransaction
                        }
                    }

                    if (isConsistent && config.entityIterableCacheHeavyEnabled) {
                        heavyIterablesCache.cacheObject(iterableIdentity, System.currentTimeMillis())
                    }

                    // Update stats and requeue if can
                    stats.incTotalJobsInterrupted()
                    when (e.reason) {
                        CACHE_ADAPTER_OBSOLETE -> stats.incTotalJobsObsolete()
                        JOB_OVERDUE -> stats.incTotalJobsOverdue()
                    }

                    // Log
                    logger.info {
                        val action = if (isConsistent) "Caching" else "Caching (inconsistent)"
                        val handle = toString(config, handle)
                        "$action forcibly stopped for handle $handle: ${e.reason.message}, caching time: $cachingTime ms"
                    }
                }
            }
        }

        private fun updateCacheSizeIfNecessary() {
            try {
                val targetSize = config.entityIterableCacheSize.toLong()
                val currentSize = cacheAdapter.size()
                if (!cacheAdapter.isWeightedCache
                    && targetSize > 0
                    && targetSize != currentSize
                ) {
                    // When cache is not weighted and config property changed.
                    // This method tries to set the maximum size of the cache if there is no concurrent modification,
                    // assuming that it converges to the desired value eventually.
                    if (cacheAdapter.trySetSize(targetSize)) {
                        logger.info("Cache size updated from $currentSize to $targetSize")
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error while updating cache size" }
            }
        }
    }

    private class TooLongEntityIterableInstantiationException(val reason: TooLongEntityIterableInstantiationReason) :
        ExodusException(reason.message)

    private enum class TooLongEntityIterableInstantiationReason(val message: String) {
        CACHE_ADAPTER_OBSOLETE("cache adapter is obsolete"), JOB_OVERDUE("caching job is overdue");
    }

    private inner class CachingCancellingPolicy(val isConsistent: Boolean) : QueryCancellingPolicy {

        private val startTime = System.currentTimeMillis()
        private val cachingTimeout =
            if (isConsistent)
                config.entityIterableCacheCachingTimeout
            else
                config.entityIterableCacheCountsCachingTimeout
        private val startCachingTimeout = config.entityIterableCacheStartCachingTimeout
        private var localCache: EntityIterableCacheAdapter? = null

        fun canStartAt(currentMillis: Long) = currentMillis - startTime < startCachingTimeout

        fun setLocalCache(localCache: EntityIterableCacheAdapter) {
            this.localCache = localCache
        }

        override fun needToCancel(): Boolean {
            return isConsistent && cacheAdapter !== localCache || System.currentTimeMillis() - startTime > cachingTimeout
        }

        override fun doCancel() {
            val reason = if (isConsistent && cacheAdapter !== localCache) {
                CACHE_ADAPTER_OBSOLETE
            } else {
                JOB_OVERDUE
            }
            throw TooLongEntityIterableInstantiationException(reason)
        }
    }
}
