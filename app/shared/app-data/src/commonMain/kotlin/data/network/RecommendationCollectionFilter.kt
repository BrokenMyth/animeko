/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.canAccessAniApiNow
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/** 首页和详情页推荐共享的收藏过滤策略, 不改变搜索或继续观看列表. */
class RecommendationCollectionFilter(
    private val subjectService: SubjectService,
    private val collectionRepository: SubjectCollectionRepository,
    private val collectionDao: SubjectCollectionDao,
    private val settingsRepository: SettingsRepository,
    private val sessionStateProvider: SessionStateProvider,
) {
    private val logger = logger<RecommendationCollectionFilter>()
    private val hideCollected = settingsRepository.uiSettings.flow
        .map { it.recommendationSettings.hideCollectedSubjects }
        .distinctUntilChanged()

    /** 收藏和设置变化时重新建立推荐分页, 使刚标记的条目退出推荐. */
    val changes: Flow<Unit> = combine(hideCollected, sessionStateProvider.stateFlow) { hide, session ->
        hide && session is SessionState.Valid
    }.flatMapLatest { enabled ->
        if (enabled) {
            combine(
                collectionDao.collectedSubjectIds().distinctUntilChanged(),
                collectionRepository.collectionsInvalidated.onStart { emit(Unit) },
            ) { _, _ -> Unit }
        } else {
            flowOf(Unit)
        }
    }

    /**
     * 每次推荐刷新读取一次完整收藏快照; 本地分页缓存不能代表账号的全部收藏.
     * 网络失败时使用已缓存的收藏, 使推荐仍可加载; 下一次刷新会再次尝试完整快照.
     */
    suspend fun excludedSubjectIds(): Set<Int> {
        if (!hideCollected.first() || !sessionStateProvider.canAccessAniApiNow()) return emptySet()
        return try {
            buildSet {
                var offset = 0
                val pageSize = 100
                while (true) {
                    val page = subjectService.getSubjectCollections(null, offset, pageSize)
                    page.filter { it.collectionType != null }.forEach { add(it.id.toInt()) }
                    if (page.size < pageSize) break
                    offset += page.size
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch collected subjects for recommendations; using local collections." }
            collectionDao.collectedSubjectIds().first().toSet()
        }
    }
}
