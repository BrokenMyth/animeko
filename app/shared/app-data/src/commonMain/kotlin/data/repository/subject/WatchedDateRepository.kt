/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AGPLv3 许可证约束，详见项目 LICENSE.txt。
 */

package me.him188.ani.app.data.repository.subject

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.Res
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.MissingResourceException

@Serializable
data class WatchedDateAccount(
    val dates: Map<Int, String> = emptyMap(),
    val appliedSeedIds: Set<String> = emptySet(),
)

@Serializable
data class WatchedDates(
    val accounts: Map<String, WatchedDateAccount> = emptyMap(),
) {
    companion object {
        val Empty = WatchedDates()
    }
}

/** 可选的本地构建资源，只导入指定账号；seedId 标识一批导入记录。 */
@Serializable
private data class WatchedDateSeed(
    val seedId: String,
    val userId: String,
    val dates: Map<Int, String>,
)

/**
 * 用户手动填写的看过日期，按账号和条目隔离，独立于可清空的收藏缓存。
 * 日期只保存日历日期，不做时区换算；删除收藏或修改状态均保留日期。
 * TODO: 看过日期的云存档与多设备同步。
 */
class WatchedDateRepository(
    private val store: DataStore<WatchedDates>,
    private val currentUserId: Flow<String?>,
) {
    private val logger = logger<WatchedDateRepository>()

    val datesFlow: Flow<Map<Int, String>> = currentUserId.distinctUntilChanged().flatMapLatest { userId ->
        if (userId == null) {
            flowOf(emptyMap())
        } else {
            flow {
                importBundledDates(userId)
                emitAll(store.data.map { it.accounts[userId]?.dates.orEmpty() })
            }
        }
    }.distinctUntilChanged()

    /** null 清除日期；空字符串和无效的日历日期均不能写入。 */
    suspend fun setDate(subjectId: Int, date: String?) {
        require(subjectId > 0)
        require(date == null || isValidWatchedDate(date))
        val userId = checkNotNull(currentUserId.first()) { "A signed-in account is required" }
        importBundledDates(userId)
        store.updateData { data ->
            val account = data.accounts[userId] ?: WatchedDateAccount()
            val dates = if (date == null) account.dates - subjectId else account.dates + (subjectId to date)
            data.copy(accounts = data.accounts + (userId to account.copy(dates = dates)))
        }
    }

    /** 导入与写入同用 DataStore 事务；已有日期优先，并记录导入批次以保留后续清除操作。 */
    private suspend fun importBundledDates(userId: String) {
        val seed = readBundledSeed() ?: return
        if (seed.userId != userId) return
        store.updateData { data ->
            val account = data.accounts[userId] ?: WatchedDateAccount()
            if (seed.seedId in account.appliedSeedIds) return@updateData data
            val validDates = seed.dates.filter { (subjectId, date) -> subjectId > 0 && isValidWatchedDate(date) }
            data.copy(
                accounts = data.accounts + (userId to account.copy(
                    dates = validDates + account.dates,
                    appliedSeedIds = account.appliedSeedIds + seed.seedId,
                )),
            )
        }
    }

    private suspend fun readBundledSeed(): WatchedDateSeed? = try {
        Json.decodeFromString<WatchedDateSeed>(Res.readBytes("files/local-watched-dates.json").decodeToString())
    } catch (e: CancellationException) {
        throw e
    } catch (_: MissingResourceException) {
        // 私人导入资源不进入版本控制，普通构建可以不包含此文件。
        null
    } catch (e: Exception) {
        logger.warn(e) { "Unable to import bundled watched dates" }
        null
    }
}

fun isValidWatchedDate(value: String): Boolean =
    value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false)
