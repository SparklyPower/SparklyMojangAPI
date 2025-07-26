package net.sparklypower.sparklymojangapi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A class that provides a mutex for a given key, with automatic cleanup.
 *
 * @param K The type of the key.
 */
class KeyedMutex<K> {
    private class RefCountedMutex {
        val mutex = Mutex()
        var refCount = 0
    }

    private val mutexes = mutableMapOf<K, RefCountedMutex>()
    private val mapMutex = Mutex()

    /**
     * Executes the given [action] under the lock of a mutex associated with the given [key].
     *
     * The mutex is created on demand and is removed when no longer in use.
     *
     * @param key The key to lock on.
     * @param owner The owner of the lock.
     * @param action The action to execute.
     * @return The result of the [action].
     */
    suspend fun <T> withLock(key: K, owner: Any? = null, action: suspend CoroutineScope.() -> T): T {
        val refCountedMutex = getOrCreateMutex(key)
        try {
            return refCountedMutex.mutex.withLock(owner) {
                coroutineScope {
                    action()
                }
            }
        } finally {
            releaseMutex(key)
        }
    }

    private suspend fun getOrCreateMutex(key: K): RefCountedMutex = mapMutex.withLock {
        val mutex = mutexes.getOrPut(key) { RefCountedMutex() }
        mutex.refCount++
        mutex
    }

    private suspend fun releaseMutex(key: K) = mapMutex.withLock {
        val mutex = mutexes[key] ?: return@withLock
        mutex.refCount--
        if (mutex.refCount == 0) {
            mutexes.remove(key)
        }
    }

    /**
     * Returns the number of mutexes currently in memory.
     */
    suspend fun size(): Int = mapMutex.withLock {
        mutexes.size
    }
}
