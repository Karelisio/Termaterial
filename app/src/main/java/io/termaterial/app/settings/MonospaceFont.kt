package io.termaterial.app.settings

import android.graphics.Typeface

/**
 * Monospace font choices. Android does not ship many distinct monospace font families, so this
 * picks between the two generic families guaranteed to exist since API 21 ("monospace" and
 * "serif-monospace") rather than bundling font files. A future iteration could let users load a
 * custom .ttf (as real Termux does with `~/.termux/font.ttf`) via the Storage Access Framework.
 */
enum class MonospaceFont(val id: String, val label: String, private val familyName: String) {
    Default("default", "Monospace", "monospace"),
    Serif("serif", "Monospace (serif)", "serif-monospace");

    fun toTypeface(): Typeface = Typeface.create(familyName, Typeface.NORMAL)

    companion object {
        fun fromId(id: String?): MonospaceFont = entries.firstOrNull { it.id == id } ?: Default
    }
}
