/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.ktor.client.call.body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.recommend.RecommendedItemInfo
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.client.apis.HomeAniApi
import me.him188.ani.client.models.AniSubjectRecommendation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import kotlin.coroutines.CoroutineContext

class RecommendationRepository(
    private val homeApi: ApiInvoker<HomeAniApi>,
    private val collectionFilter: RecommendationCollectionFilter,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_
) : Repository() {
    fun recommendedSubjectsPager(): Flow<PagingData<RecommendedItemInfo>> {
        // 收藏状态按每轮推荐读取快照，当前卡片在用户主动刷新前保持稳定。
        return Pager(defaultPagingConfig, initialKey = 0) {
            HomeRecommendationPagingSource()
        }.flow
    }

    private inner class HomeRecommendationPagingSource : PagingSource<Int, RecommendedItemInfo>() {
        private val filterMutex = Mutex()
        private var excludedIds: Set<Int>? = null

        override fun getRefreshKey(state: PagingState<Int, RecommendedItemInfo>): Int =
            state.pages.lastOrNull()?.nextKey ?: 0

        private suspend fun excludedSubjectIds(): Set<Int> = filterMutex.withLock {
            excludedIds ?: collectionFilter.excludedSubjectIds().also { excludedIds = it }
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RecommendedItemInfo> {
            val initialOffset = params.key ?: 0
            val loadSize = params.loadSize
            return runWrappingExceptionAsLoadResult<Int, RecommendedItemInfo> {
                val excludedIds = excludedSubjectIds()
                var offset = initialOffset
                var nextOffset: Int?
                var list: List<RecommendedItemInfo>
                var canWrap = params is LoadParams.Refresh && initialOffset > 0
                do {
                    val response = withContext(ioDispatcher) {
                        homeApi {
                            getHomeRecommendations(
                                offset = offset,
                                limit = loadSize,
                            ).body()
                        }
                    }
                    list = response.items.mapNotNull {
                        it.toRecommendedSubjectInfo()?.takeUnless { subject -> subject.bangumiId in excludedIds }
                    }
                    // 分页偏移基于服务器返回的数量, 与过滤后可见条目的数量无关.
                    nextOffset = if (response.items.isEmpty() || offset + response.items.size >= response.total) {
                        null
                    } else {
                        offset + response.items.size
                    }
                    // 空白页没有可见条目触发 Compose 继续加载, 由当前请求跨过被完全过滤的页.
                    if (list.isEmpty() && nextOffset == null && canWrap) {
                        // 新批次末尾没有可见条目时最多回到开头一次，追加加载不回绕。
                        nextOffset = 0
                        canWrap = false
                    }
                    nextOffset?.let { offset = it }
                } while (list.isEmpty() && nextOffset != null)

                LoadResult.Page<Int, RecommendedItemInfo>(
                    list.shuffled(),
                    // 每轮推荐只向后加载，避免从非零起点自动前置上一批条目。
                    prevKey = null,
                    nextKey = nextOffset,
                )
            }.also {
                if (it is LoadResult.Error) {
                    logger.error(it.throwable) { "Failed to load home recommendations." }
                }
            }
        }
    }

    private fun AniSubjectRecommendation.toRecommendedSubjectInfo(): RecommendedSubjectInfo? {
        val id = subjectId?.takeIf { it > 0 }?.toInt() ?: return null
        return RecommendedSubjectInfo(
            bangumiId = id,
            nameCn = subjectNameCn,
            name = subjectName,
            imageLarge = imageUrl,
        )
    }
}
