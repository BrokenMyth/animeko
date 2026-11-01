/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AGPLv3 许可证约束，详见项目 LICENSE.txt。
 */

package me.him188.ani.app.ui.subject.collection.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_collection_cancel
import me.him188.ani.app.ui.lang.watched_date_clear
import me.him188.ani.app.ui.lang.watched_date_edit
import me.him188.ani.app.ui.lang.watched_date_save
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** 卡片日期与菜单动作共用的编辑入口，保存失败时保留输入。 */
@Composable
fun WatchedDateDialog(
    initialDate: String?,
    onSave: suspend (String?) -> LoadError?,
    onDismissRequest: () -> Unit,
) {
    val initialDateMillis = remember(initialDate) { initialDate?.toPickerDateMillis() }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis,
        // 与本地日期存储允许的年份范围一致，已有日期始终可以再次编辑。
        yearRange = 1..9999,
    )
    var isSaving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val selectedDate = datePickerState.selectedDateMillis?.toWatchedDate()

    fun save(date: String?) {
        if (isSaving) return
        isSaving = true
        scope.launch {
            try {
                val error = onSave(date)
                if (error == null) onDismissRequest() else toaster.showLoadError(error)
            } finally {
                isSaving = false
            }
        }
    }

    DatePickerDialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        confirmButton = {
            TextButton(onClick = { selectedDate?.let(::save) }, enabled = selectedDate != null && !isSaving) {
                Text(stringResource(Lang.watched_date_save))
            }
        },
        dismissButton = {
            Row {
                if (initialDate != null) {
                    TextButton(onClick = { save(null) }, enabled = !isSaving) {
                        Text(stringResource(Lang.watched_date_clear))
                    }
                }
                TextButton(onClick = onDismissRequest, enabled = !isSaving) {
                    Text(stringResource(Lang.subject_collection_cancel))
                }
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            title = {
                Text(
                    stringResource(Lang.watched_date_edit),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp),
                )
            },
            showModeToggle = true,
        )
    }
}

/** Material 日期选择器以 UTC 零点表示日历日期，存储日期不受设备时区影响。 */
private fun String.toPickerDateMillis(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}.getOrNull()

private fun Long.toWatchedDate(): String =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date.toString()
