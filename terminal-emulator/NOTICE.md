# Third-party code notice

This module (`terminal-emulator`) is adapted from the `terminal-emulator`
module of [termux/termux-app](https://github.com/termux/termux-app), which in
turn descends from [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator).

Unlike the rest of the termux-app repository (GPLv3), the `terminal-emulator`
and `terminal-view` modules are distributed under the **Apache License 2.0** —
see [`LICENSE`](LICENSE) in this directory.

Only the `terminal-emulator` and `terminal-view` modules were taken from
termux-app; no other part of that project (bootstrap installer, plugins,
app shell, etc.) is included here. The code has been adapted to build as a
standalone Gradle module (Kotlin DSL build script, project version catalog)
inside Termaterial; the Java/JNI sources are otherwise unmodified from the
upstream project at the time of import.
