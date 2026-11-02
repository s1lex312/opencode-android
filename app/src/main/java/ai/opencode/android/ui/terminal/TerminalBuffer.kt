package ai.opencode.android.ui.terminal

/**
 * Minimal VT100/xterm screen model for the PTY view.
 *
 * Covers what shells, `git`, compilers and most CLIs emit: printable text, CR/LF/BS/TAB,
 * cursor movement (CUU/CUD/CUF/CUB/CUP/CHA/VPA), erase in line/display, SGR colours (16, 256 and
 * truecolor approximated), save/restore cursor and the alternate screen. Full-screen TUIs work
 * at a basic level; anything unknown is swallowed instead of printed as garbage.
 */
class TerminalBuffer(cols: Int = 80, rows: Int = 24, private val scrollbackLimit: Int = 2000) {

    data class Style(val fg: Int = DEFAULT, val bg: Int = DEFAULT, val bold: Boolean = false, val inverse: Boolean = false)

    data class Cell(val char: Char = ' ', val style: Style = Style())

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    /** Scrollback (oldest first) followed by the live screen. */
    private val lines = ArrayList<Array<Cell>>()
    private var savedMain: ArrayList<Array<Cell>>? = null

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var savedRow = 0
    private var savedCol = 0
    private var style = Style()

    private enum class Mode { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private var mode = Mode.NORMAL
    private val params = StringBuilder()

    /** Bumped on every change so Compose can observe a cheap value. */
    var version = 0L
        private set

    init {
        repeat(rows) { lines += blankLine() }
    }

    /** Index of the first screen row inside [lines]. */
    private val top: Int get() = lines.size - rows

    val lineCount: Int get() = lines.size

    fun line(index: Int): Array<Cell> = lines[index]

    /** Row of the cursor in [lines] coordinates. */
    val cursorLine: Int get() = top + cursorRow

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0 || (newCols == cols && newRows == rows)) return
        for (i in lines.indices) lines[i] = lines[i].copyOf(newCols).let { arr -> Array(newCols) { arr[it] ?: Cell() } }
        while (lines.size < newRows) lines.add(0, blankLine(newCols))
        cols = newCols
        rows = newRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        version++
    }

    fun write(text: String) {
        for (ch in text) feed(ch)
        version++
    }

    private fun feed(ch: Char) {
        when (mode) {
            Mode.NORMAL -> when (ch) {
                '\u001b' -> mode = Mode.ESC
                '\r' -> cursorCol = 0
                '\n', '\u000b', '\u000c' -> lineFeed()
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> cursorCol = ((cursorCol / 8) + 1).times(8).coerceAtMost(cols - 1)
                '\u0007', '\u0000', '\u000e', '\u000f' -> Unit
                else -> if (ch >= ' ') put(ch)
            }

            Mode.ESC -> {
                mode = Mode.NORMAL
                when (ch) {
                    '[' -> {
                        params.setLength(0)
                        mode = Mode.CSI
                    }
                    ']' -> {
                        params.setLength(0)
                        mode = Mode.OSC
                    }
                    '(', ')', '*', '+' -> mode = Mode.CHARSET
                    '7' -> saveCursor()
                    '8' -> restoreCursor()
                    'D' -> lineFeed()
                    'E' -> {
                        cursorCol = 0
                        lineFeed()
                    }
                    'M' -> reverseLineFeed()
                    'c' -> reset()
                }
            }

            Mode.CHARSET -> mode = Mode.NORMAL

            Mode.OSC -> when (ch) {
                '\u0007' -> mode = Mode.NORMAL
                '\u001b' -> mode = Mode.OSC_ESC
                else -> Unit
            }

            Mode.OSC_ESC -> mode = if (ch == '\\') Mode.NORMAL else Mode.OSC

            Mode.CSI -> if (ch in '@'..'~') {
                mode = Mode.NORMAL
                csi(ch, params.toString())
            } else {
                params.append(ch)
            }
        }
    }

    private fun csi(command: Char, raw: String) {
        val private = raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("=")
        val values = raw.trimStart('?', '>', '=').split(';').map { it.toIntOrNull() }
        fun arg(index: Int, default: Int): Int = values.getOrNull(index)?.takeIf { it != 0 || default == 0 } ?: default

        if (private) {
            if ((command == 'h' || command == 'l') && values.any { it == 1049 || it == 47 || it == 1047 }) {
                alternateScreen(command == 'h')
            }
            return
        }
        when (command) {
            'A' -> cursorRow = (cursorRow - arg(0, 1)).coerceAtLeast(0)
            'B', 'e' -> cursorRow = (cursorRow + arg(0, 1)).coerceAtMost(rows - 1)
            'C', 'a' -> cursorCol = (cursorCol + arg(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - arg(0, 1)).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + arg(0, 1)).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> {
                cursorRow = (cursorRow - arg(0, 1)).coerceAtLeast(0)
                cursorCol = 0
            }
            'G', '`' -> cursorCol = (arg(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(arg(0, 0))
            'K' -> eraseLine(arg(0, 0))
            'X' -> {
                val line = lines[cursorLine]
                for (c in cursorCol until (cursorCol + arg(0, 1)).coerceAtMost(cols)) line[c] = Cell(style = style.blank())
            }
            'P' -> {
                val line = lines[cursorLine]
                val n = arg(0, 1).coerceAtMost(cols - cursorCol)
                for (c in cursorCol until cols) line[c] = if (c + n < cols) line[c + n] else Cell(style = style.blank())
            }
            '@' -> {
                val line = lines[cursorLine]
                val n = arg(0, 1).coerceAtMost(cols - cursorCol)
                for (c in cols - 1 downTo cursorCol) line[c] = if (c - n >= cursorCol) line[c - n] else Cell(style = style.blank())
            }
            'L' -> repeat(arg(0, 1)) {
                lines.removeAt(lines.size - 1)
                lines.add(cursorLine, blankLine())
            }
            'M' -> repeat(arg(0, 1)) {
                lines.removeAt(cursorLine)
                lines.add(blankLine())
            }
            'm' -> sgr(values)
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    private fun sgr(values: List<Int?>) {
        if (values.isEmpty() || (values.size == 1 && values[0] == null)) {
            style = Style()
            return
        }
        var i = 0
        while (i < values.size) {
            when (val code = values[i] ?: 0) {
                0 -> style = Style()
                1 -> style = style.copy(bold = true)
                22 -> style = style.copy(bold = false)
                7 -> style = style.copy(inverse = true)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(fg = code - 30)
                39 -> style = style.copy(fg = DEFAULT)
                in 40..47 -> style = style.copy(bg = code - 40)
                49 -> style = style.copy(bg = DEFAULT)
                in 90..97 -> style = style.copy(fg = code - 90 + 8)
                in 100..107 -> style = style.copy(bg = code - 100 + 8)
                38, 48 -> {
                    val color = when (values.getOrNull(i + 1)) {
                        5 -> values.getOrNull(i + 2)?.also { i += 2 }
                        2 -> {
                            val r = values.getOrNull(i + 2) ?: 0
                            val g = values.getOrNull(i + 3) ?: 0
                            val b = values.getOrNull(i + 4) ?: 0
                            i += 4
                            TRUECOLOR or (r shl 16) or (g shl 8) or b
                        }
                        else -> null
                    }
                    if (color != null) style = if (code == 38) style.copy(fg = color) else style.copy(bg = color)
                }
            }
            i++
        }
    }

    private fun put(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        lines[cursorLine][cursorCol] = Cell(ch, style)
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            lines += blankLine()
            if (savedMain == null) {
                while (lines.size > rows + scrollbackLimit) lines.removeAt(0)
            } else {
                // The alternate screen has no scrollback.
                lines.removeAt(0)
            }
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow > 0) {
            cursorRow--
        } else {
            lines.removeAt(lines.size - 1)
            lines.add(top, blankLine())
        }
    }

    private fun eraseLine(mode: Int) {
        val line = lines[cursorLine]
        val range = when (mode) {
            1 -> 0..cursorCol.coerceAtMost(cols - 1)
            2 -> 0 until cols
            else -> cursorCol until cols
        }
        for (c in range) line[c] = Cell(style = style.blank())
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            1 -> {
                for (r in top until cursorLine) lines[r] = blankLine()
                eraseLine(1)
            }
            2, 3 -> {
                for (r in top until lines.size) lines[r] = blankLine()
                if (mode == 3 && savedMain == null) {
                    while (lines.size > rows) lines.removeAt(0)
                }
            }
            else -> {
                eraseLine(0)
                for (r in cursorLine + 1 until lines.size) lines[r] = blankLine()
            }
        }
    }

    private fun alternateScreen(enable: Boolean) {
        if (enable && savedMain == null) {
            savedMain = ArrayList(lines)
            saveCursor()
            lines.clear()
            repeat(rows) { lines += blankLine() }
            cursorRow = 0
            cursorCol = 0
        } else if (!enable) {
            savedMain?.let {
                lines.clear()
                lines.addAll(it)
                while (lines.size < rows) lines += blankLine()
            }
            savedMain = null
            restoreCursor()
        }
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
    }

    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
    }

    private fun reset() {
        lines.clear()
        repeat(rows) { lines += blankLine() }
        cursorRow = 0
        cursorCol = 0
        style = Style()
        savedMain = null
    }

    private fun Style.blank(): Style = Style(bg = bg)

    private fun blankLine(width: Int = cols): Array<Cell> = Array(width) { Cell() }

    /** Plain text of the whole buffer, trailing blanks trimmed (used for copy and tests). */
    fun text(): String = lines.joinToString("\n") { line -> String(CharArray(line.size) { line[it].char }).trimEnd() }.trimEnd()

    companion object {
        const val DEFAULT = -1

        /** Flag marking a 24-bit RGB value in [Style.fg]/[Style.bg]. */
        const val TRUECOLOR = 0x1000000
    }
}
