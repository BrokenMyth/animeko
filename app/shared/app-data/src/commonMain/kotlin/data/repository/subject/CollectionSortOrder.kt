/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.repository.subject

import androidx.room.RoomRawQuery
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

enum class CollectionSortOrder {
    DEFAULT,
    WATCHED_DATE_DESC,
    WATCHED_DATE_ASC,
}

internal val CollectionsFilterQuery.isWatchedDateSorted: Boolean
    get() = type == UnifiedCollectionType.DONE && sortOrder != CollectionSortOrder.DEFAULT

/** 日期只在内存中比较，SQL 排名仅包含整型条目 ID；同一天使用收藏更新时间稳定排序。 */
internal fun CollectionsFilterQuery.watchedDatePagingQuery(includeNsfw: Boolean): RoomRawQuery {
    val validDates = watchedDates.filter { (subjectId, date) -> subjectId > 0 && isValidWatchedDate(date) }
    val sortedDates = validDates.values.distinct().let { dates ->
        if (sortOrder == CollectionSortOrder.WATCHED_DATE_ASC) dates.sorted() else dates.sortedDescending()
    }
    val ranks = sortedDates.withIndex().associate { it.value to it.index }
    val rankExpression = if (validDates.isEmpty()) {
        null
    } else {
        validDates.entries.sortedBy { it.key }.joinToString(
            separator = " ",
            prefix = "CASE subjectId ",
            postfix = " ELSE ${ranks.size} END",
        ) { (subjectId, date) -> "WHEN $subjectId THEN ${ranks.getValue(date)}" }
    }
    val orderBy = listOfNotNull(rankExpression?.let { "$it ASC" }, "lastUpdated DESC", "subjectId DESC")
        .joinToString(", ")
    return RoomRawQuery(
        """
        SELECT * FROM subject_collection
        WHERE collectionType = '${UnifiedCollectionType.DONE.name}'
        ${if (includeNsfw) "" else "AND NOT nsfw"}
        ORDER BY $orderBy
        """.trimIndent(),
    )
}
