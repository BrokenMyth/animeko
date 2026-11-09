/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import me.him188.ani.app.ui.foundation.effects.onPointerEventMultiplatform

/** 记录当前窗口真正移出后重新进入的次数，不记录子控件之间的移动。 */
@Stable
class WindowPointerState {
    var reentryCount: Int by mutableIntStateOf(0)
        private set

    private var pointerLeftWindow = false

    internal fun onPointerExited() {
        pointerLeftWindow = true
    }

    internal fun onPointerEntered() {
        if (!pointerLeftWindow) return
        pointerLeftWindow = false
        reentryCount++
    }
}

val LocalWindowPointerState = staticCompositionLocalOf<WindowPointerState?> { null }

/**
 * 在整个窗口最外层观察鼠标边界，包括标题栏、视频和侧栏。
 * 只有指针坐标实际移出窗口边界才记录离开，避免弹层遮挡或子控件显隐造成的 Exit 解除隐藏。
 * 仅响应进入/离开事件，不消费输入，不在鼠标移动时刷新状态；观察随组合销毁自动清理。
 */
@Composable
fun WindowPointerBoundary(content: @Composable () -> Unit) {
    val state = remember { WindowPointerState() }
    CompositionLocalProvider(LocalWindowPointerState provides state) {
        Box(
            Modifier.fillMaxSize()
                .onPointerEventMultiplatform(PointerEventType.Exit, PointerEventPass.Initial) { event ->
                    val pointer = event.changes.firstOrNull { it.type == PointerType.Mouse }
                        ?: return@onPointerEventMultiplatform
                    val position = pointer.position
                    if (position.x < 0 || position.y < 0 || position.x >= size.width || position.y >= size.height) {
                        state.onPointerExited()
                    }
                }
                .onPointerEventMultiplatform(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                    if (event.changes.any { it.type == PointerType.Mouse }) state.onPointerEntered()
                },
        ) {
            content()
        }
    }
}
