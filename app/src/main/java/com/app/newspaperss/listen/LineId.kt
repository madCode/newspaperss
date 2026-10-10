package com.app.newspaperss.listen

/**
 * Where a line [ListenPlayer] asks for is: its edition, page and line, of how many [lines] on
 * the page, and the play it belongs to.
 */
data class LineId(val generation: Int, val editionId: Long, val page: Int, val line: Int, val lines: Int) {
    override fun toString() = "$generation:$editionId:$page:$line:$lines"

    companion object {
        fun parse(id: String): LineId? {
            val parts = id.split(':')
            if (parts.size != 5) return null
            return LineId(
                parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null,
                parts[4].toIntOrNull() ?: return null,
            )
        }
    }
}
