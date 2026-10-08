package io.termaterial.app.terminal

import androidx.compose.runtime.State
import com.termux.terminal.TerminalSession

/** One entry in the multi-session tab row. */
class TerminalTab(
    val id: String,
    /** 1-based, in opening order: labels the tab until the shell sets a title of its own. */
    val number: Int,
    val session: TerminalSession,
    val client: AppTerminalClient,
    val title: State<String?>,
    /** True once the shell has exited; the tab then stays open until Enter is pressed. */
    val finished: State<Boolean>,
)
