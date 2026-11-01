/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.tools.MonoTasker
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.coroutines.cancellation.CancellationException

/**
 * 编辑条目收藏类型的交互. 由 [EditableSubjectCollectionTypeState] 实现, 也可由页面状态委托实现.
 */
interface SubjectCollectionTypeEditActions {
    /**
     * @return 失败原因, 成功为 `null`.
     */
    suspend fun setSelfCollectionType(new: UnifiedCollectionType): LoadError?

    /**
     * TV 交互接口的兼容属性。完成动作包含全部剧集，不需要额外确认。
     */
    val shouldOfferMarkAllWatched: Boolean get() = false

    fun setAllEpisodesWatched()

    /**
     * 等待完成的 [setAllEpisodesWatched].
     *
     * @return 失败原因, 成功为 `null`.
     */
    suspend fun setAllEpisodesWatchedAwait(): LoadError?
    fun dismissSetAllEpisodesDoneDialog() {}

    companion object Noop : SubjectCollectionTypeEditActions {
        override suspend fun setSelfCollectionType(new: UnifiedCollectionType): LoadError? = null
        override fun setAllEpisodesWatched() {}
        override suspend fun setAllEpisodesWatchedAwait(): LoadError? = null
    }
}

@Stable
class EditableSubjectCollectionTypeState(
    selfCollectionTypeFlow: Flow<UnifiedCollectionType>,
    @Suppress("UNUSED_PARAMETER") hasAnyUnwatched: suspend () -> Boolean,
    private val onSetSelfCollectionType: suspend (UnifiedCollectionType) -> Unit,
    private val onSetAllEpisodesWatched: suspend () -> Unit,
    private val backgroundScope: CoroutineScope,
) : SubjectCollectionTypeEditActions {
    data class Presentation(
        val selfCollectionType: UnifiedCollectionType,
        val isSetSelfCollectionTypeWorking: Boolean,
        val isPlaceholder: Boolean = false,
    ) {
        companion object {
            val Placeholder = Presentation(
                UnifiedCollectionType.WISH,
                false,
                isPlaceholder = true,
            )
        }
    }

    /**
     * [setSelfCollectionType] 的后台任务
     */
    private val setSelfCollectionTypeTasker = MonoTasker(backgroundScope)

    /**
     * [setAllEpisodesWatched] 的后台任务
     */
    private val setAllEpisodesWatchedTasker = MonoTasker(backgroundScope)

    /**
     * 待 UI 显示的数据
     */
    val presentationFlow: StateFlow<Presentation> =
        combine(
            selfCollectionTypeFlow,
            setSelfCollectionTypeTasker.isRunning,
        ) { type, setSelfCollectionTypeTaskerWorking ->
            Presentation(
                selfCollectionType = type,
                isSetSelfCollectionTypeWorking = setSelfCollectionTypeTaskerWorking,
            )
        }.stateIn(
            backgroundScope,
            SharingStarted.WhileSubscribed(5000),
            initialValue = Presentation.Placeholder,
        )

    override suspend fun setSelfCollectionType(new: UnifiedCollectionType): LoadError? {
        return setSelfCollectionTypeTasker.async {
            try {
                onSetSelfCollectionType(new)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LoadError.fromException(e)
            }
        }.await()
    }

    override fun setAllEpisodesWatched() {
        backgroundScope.launch { setAllEpisodesWatchedAwait() }
    }

    override suspend fun setAllEpisodesWatchedAwait(): LoadError? = setAllEpisodesWatchedTasker.async {
        LoadError.runAndWrapOrThrowCancellation { onSetAllEpisodesWatched() }
    }.await()

}

/**
 * 展示当前收藏状态的按钮, 点击弹出 [EditCollectionTypeDropDown].
 * 设置为 "看过" 时，收藏用例会同时标记全部剧集。
 */
@Composable
fun EditableSubjectCollectionTypeButton(
    state: EditableSubjectCollectionTypeState,
    modifier: Modifier = Modifier,
) {
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    EditableSubjectCollectionTypeButton(presentation, state, modifier)
}

/**
 * 展示当前收藏状态的按钮, 点击弹出 [EditCollectionTypeDropDown]。
 *
 * 展示数据与动作分离的版本, 供页面级 UiState 使用.
 */
@Composable
fun EditableSubjectCollectionTypeButton(
    presentation: EditableSubjectCollectionTypeState.Presentation,
    actions: SubjectCollectionTypeEditActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    SubjectCollectionTypeButton(
        presentation.selfCollectionType,
        onEdit = {
            scope.launch {
                val error = actions.setSelfCollectionType(it)
                error?.let(toaster::showLoadError)
            }
        },
        modifier = modifier.placeholder(presentation.isPlaceholder),
        enabled = !presentation.isSetSelfCollectionTypeWorking,
    )
}

/**
 * 播放页的兼容入口。收藏完成动作由用例处理，无附加确认界面。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun EditableSubjectCollectionTypeDialogsHost(
    state: EditableSubjectCollectionTypeState,
) {}

@TestOnly
@Composable
fun rememberTestEditableSubjectCollectionTypeState(type: UnifiedCollectionType = UnifiedCollectionType.WISH): EditableSubjectCollectionTypeState {
    val backgroundScope = rememberCoroutineScope()
    val selfCollectionType = remember {
        MutableStateFlow(type)
    }
    return remember {
        createTestEditableSubjectCollectionTypeState(selfCollectionType, backgroundScope)
    }
}

@TestOnly
fun createTestEditableSubjectCollectionTypeState(
    selfCollectionType: MutableStateFlow<UnifiedCollectionType>,
    backgroundScope: CoroutineScope
) = EditableSubjectCollectionTypeState(
    selfCollectionType,
    hasAnyUnwatched = { false },
    onSetSelfCollectionType = {
        selfCollectionType.value = it
    },
    onSetAllEpisodesWatched = { },
    backgroundScope,
)
