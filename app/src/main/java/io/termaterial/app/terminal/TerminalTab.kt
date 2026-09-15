package io.termaterial.app.terminal

import androidx.compose.runtime.MutableState
import com.termux.terminal.TerminalSession

/** One entry in the multi-session tab row. */
class TerminalTab(
    val id: String,
    val session: TerminalSession,
    val client: AppTerminalClient,
    val title: MutableState<String?>,
)
