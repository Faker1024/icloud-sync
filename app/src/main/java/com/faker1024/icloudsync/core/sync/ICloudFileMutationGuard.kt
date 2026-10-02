package com.faker1024.icloudsync.core.sync

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Transfers remain concurrent. Destructive operations require exclusive access. */
@Singleton
class ICloudFileMutationGuard @Inject constructor() {
    private val mutex = Mutex()
    private var transfers = 0
    private var deleting = false

    suspend fun <T> transfer(block: suspend () -> T): T {
        mutex.withLock {
            if (deleting) throw IOException("正在清理相似图片，下载稍后自动重试")
            transfers++
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) { mutex.withLock { transfers-- } }
        }
    }

    suspend fun <T> deletion(block: suspend () -> T): T {
        mutex.withLock {
            check(!deleting && transfers == 0) { "有文件正在下载或迁移，请完成或暂停后再删除" }
            deleting = true
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) { mutex.withLock { deleting = false } }
        }
    }
}
