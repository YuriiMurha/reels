package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.data.db.ApiRequestDao
import io.github.yuriimurha.reels.data.db.ApiRequestEntity

class RoomRequestLog(private val dao: ApiRequestDao) : RequestLog {
    override suspend fun record(at: Long) {
        dao.insert(ApiRequestEntity(at = at))
        dao.deleteUpTo(at - Pacer.DAY_MS)
    }

    override suspend fun countSince(since: Long): Int = dao.countSince(since)

    override suspend fun oldestSince(since: Long): Long? = dao.oldestSince(since)

    override suspend fun latest(): Long? = dao.latest()
}
