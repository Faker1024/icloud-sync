package com.faker1024.icloudsync.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ICloudFileMutationGuardTest {
    @Test
    fun `downloads can overlap while deletion is blocked`() = runBlocking {
        val guard = ICloudFileMutationGuard()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { guard.transfer { entered.complete(Unit); release.await() } }
        entered.await()
        val second = async { guard.transfer { true } }
        assertTrue(second.await())
        assertTrue(runCatching { guard.deletion { error("must not enter") } }.isFailure)
        release.complete(Unit)
        first.join()
        assertTrue(guard.deletion { true })
    }

    @Test
    fun `deletion blocks downloads and cancelled deletion releases guard`() = runBlocking {
        val guard = ICloudFileMutationGuard()
        val entered = CompletableDeferred<Unit>()
        val job = launch { guard.deletion { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        assertTrue(runCatching { guard.transfer { error("must not enter") } }.isFailure)
        job.cancelAndJoin()
        assertTrue(guard.transfer { true })
    }
}
