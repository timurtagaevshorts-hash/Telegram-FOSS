package uz.kodlikino.top4.games

/**
 * Rus damasi (shashka) qoidalari — professional daraja.
 *
 * Qoidalar (rus damasi rasmiy):
 *  - 8×8, faqat qora kataklar; oq yuqoriga (kichik qator indeksiga) yuradi.
 *  - Oddiy dona faqat oldinga bir katak yuradi, lekin urishni to‘rt yo‘nalishda ham qiladi.
 *  - Dama diagonal bo‘ylab istalgan masofaga yuradi va uradi.
 *  - Urish MAJBURIY. Bir nechta variant bo‘lsa — eng ko‘p dona oladigan variant(lar).
 *  - Urilgan donalar butun zanjir tugagach olinadi; shu sababli bir donadan ikki marta
 *    oshib bo‘lmaydi va zanjir urilgan dona ustidan qaytib o‘ta olmaydi.
 *  - Zanjir o‘rtasida dama qatoriga chiqqan dona darhol dama bo‘lib, zanjirni dama
 *    sifatida davom ettiradi va oxirigacha dama bo‘lib qoladi.
 *  - 15 yurish (har tomondan) faqat damalar bilan, urish va oddiy dona yurishisiz — durang.
 *
 * Doska satr sifatida saqlanadi: 64 belgi, indeks = row * 8 + col.
 */
object CheckersEngine {

    const val EMPTY = '.'
    const val WHITE_MAN = 'w'
    const val WHITE_KING = 'W'
    const val BLACK_MAN = 'b'
    const val BLACK_KING = 'B'

    const val SIZE = 8
    const val CELLS = SIZE * SIZE

    /** Rasmiy durang chegarasi: har tomondan 15 yurish = 30 javob. */
    const val IDLE_DRAW_PLIES = 30

    private val DIRS = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

    data class Sq(val row: Int, val col: Int) {
        val index: Int get() = row * SIZE + col

        fun key(): String = "$row,$col"

        companion object {
            fun of(index: Int): Sq = Sq(index / SIZE, index % SIZE)

            fun parse(raw: String?): Sq? {
                val parts = raw?.trim()?.split(",") ?: return null
                if (parts.size != 2) return null
                val r = parts[0].toIntOrNull() ?: return null
                val c = parts[1].toIntOrNull() ?: return null
                if (!isPlayable(r, c)) return null
                return Sq(r, c)
            }
        }
    }

    /**
     * Bitta to‘liq yurish. [path] — dona bosib o‘tadigan kataklar ketma-ketligi
     * (zanjirning barcha oraliq qo‘nishlari + oxirgi katak), animatsiya uchun.
     */
    data class Move(
        val from: Sq,
        val to: Sq,
        val captures: List<Sq> = emptyList(),
        val path: List<Sq> = emptyList(),
        val endsAsKing: Boolean = false
    ) {
        val isCapture: Boolean get() = captures.isNotEmpty()

        fun wire(): String {
            val steps = if (path.isEmpty()) listOf(to) else path
            return from.key() + ">" + steps.joinToString(">") { it.key() }
        }
    }

    enum class Side(val code: String) {
        WHITE("w"),
        BLACK("b");

        fun opposite(): Side = if (this == WHITE) BLACK else WHITE

        companion object {
            fun fromCode(code: String?): Side? = when (code?.trim()?.lowercase()) {
                "w", "white", "oq" -> WHITE
                "b", "black", "qora" -> BLACK
                else -> null
            }
        }
    }

    enum class End(val code: String) {
        WHITE_WIN("white_win"),
        BLACK_WIN("black_win"),
        DRAW("draw");

        companion object {
            fun fromCode(code: String?): End? = when (code?.trim()?.lowercase()) {
                "white_win", "white" -> WHITE_WIN
                "black_win", "black" -> BLACK_WIN
                "draw" -> DRAW
                else -> null
            }
        }
    }

    data class Applied(
        val board: String,
        val nextTurn: Side,
        val idlePlies: Int,
        val end: End?
    )

    // ------------------------------------------------------------------ basics

    fun isPlayable(row: Int, col: Int): Boolean =
        row in 0..7 && col in 0..7 && (row + col) % 2 == 1

    fun initialBoard(): String {
        val cells = CharArray(CELLS) { EMPTY }
        for (r in 0..7) {
            for (c in 0..7) {
                if (!isPlayable(r, c)) continue
                when (r) {
                    in 0..2 -> cells[r * SIZE + c] = BLACK_MAN
                    in 5..7 -> cells[r * SIZE + c] = WHITE_MAN
                }
            }
        }
        return String(cells)
    }

    fun isValidBoard(board: String?): Boolean = board != null && board.length == CELLS

    fun pieceAt(board: String, sq: Sq): Char =
        if (!isValidBoard(board) || !isPlayable(sq.row, sq.col)) EMPTY
        else board[sq.index]

    fun isKing(piece: Char): Boolean = piece == WHITE_KING || piece == BLACK_KING

    fun isMan(piece: Char): Boolean = piece == WHITE_MAN || piece == BLACK_MAN

    fun sideOf(piece: Char): Side? = when (piece) {
        WHITE_MAN, WHITE_KING -> Side.WHITE
        BLACK_MAN, BLACK_KING -> Side.BLACK
        else -> null
    }

    fun belongsTo(piece: Char, side: Side): Boolean = sideOf(piece) == side

    fun count(board: String, side: Side): Int {
        if (!isValidBoard(board)) return 0
        var n = 0
        for (ch in board) if (sideOf(ch) == side) n++
        return n
    }

    private fun kingChar(side: Side): Char =
        if (side == Side.WHITE) WHITE_KING else BLACK_KING

    private fun promoRow(side: Side): Int = if (side == Side.WHITE) 0 else 7

    private fun forwardDirs(side: Side): Array<Pair<Int, Int>> =
        if (side == Side.WHITE) arrayOf(-1 to -1, -1 to 1) else arrayOf(1 to -1, 1 to 1)

    // ------------------------------------------------------------- move search

    /**
     * Yurish mumkin bo‘lgan to‘liq yurishlar ro‘yxati.
     * Urish bor bo‘lsa — faqat MAKSIMAL urish zanjirlari qaytadi.
     */
    fun legalMoves(board: String, turn: Side): List<Move> {
        if (!isValidBoard(board)) return emptyList()
        val cells = board.toCharArray()
        val captures = ArrayList<Move>()

        for (index in 0 until CELLS) {
            val sq = Sq.of(index)
            if (!isPlayable(sq.row, sq.col)) continue
            val piece = cells[index]
            if (sideOf(piece) != turn) continue
            collectSequences(cells, sq, sq, turn, isKing(piece), ArrayList(), ArrayList(), false, captures)
        }

        if (captures.isNotEmpty()) {
            var best = 0
            for (m in captures) if (m.captures.size > best) best = m.captures.size
            val result = ArrayList<Move>()
            for (m in captures) if (m.captures.size == best) result.add(m)
            return result
        }

        val quiet = ArrayList<Move>()
        for (index in 0 until CELLS) {
            val sq = Sq.of(index)
            if (!isPlayable(sq.row, sq.col)) continue
            val piece = cells[index]
            if (sideOf(piece) != turn) continue
            if (isKing(piece)) {
                collectKingQuiet(cells, sq, turn, quiet)
            } else {
                collectManQuiet(cells, sq, turn, quiet)
            }
        }
        return quiet
    }

    fun hasAnyMove(board: String, turn: Side): Boolean {
        if (!isValidBoard(board)) return false
        val cells = board.toCharArray()
        for (index in 0 until CELLS) {
            val sq = Sq.of(index)
            if (!isPlayable(sq.row, sq.col)) continue
            val piece = cells[index]
            if (sideOf(piece) != turn) continue
            if (isKing(piece)) {
                val quiet = ArrayList<Move>(1)
                collectKingQuiet(cells, sq, turn, quiet)
                if (quiet.isNotEmpty()) return true
            } else {
                val quiet = ArrayList<Move>(1)
                collectManQuiet(cells, sq, turn, quiet)
                if (quiet.isNotEmpty()) return true
            }
            val captures = ArrayList<Move>(1)
            collectSequences(cells, sq, sq, turn, isKing(piece), ArrayList(), ArrayList(), false, captures)
            if (captures.isNotEmpty()) return true
        }
        return false
    }

    /** Shu dona uchun maksimal zanjirlar (UI: qaysi donalar urishi mumkinligini ko‘rsatish). */
    fun captureMovesFrom(board: String, from: Sq, turn: Side): List<Move> {
        if (!isValidBoard(board)) return emptyList()
        val cells = board.toCharArray()
        val piece = cells[from.index]
        if (sideOf(piece) != turn) return emptyList()
        val all = ArrayList<Move>()
        collectSequences(cells, from, from, turn, isKing(piece), ArrayList(), ArrayList(), false, all)
        if (all.isEmpty()) return emptyList()
        var best = 0
        for (m in all) if (m.captures.size > best) best = m.captures.size
        val result = ArrayList<Move>()
        for (m in all) if (m.captures.size == best) result.add(m)
        return result
    }

    fun findMove(board: String, turn: Side, from: Sq, to: Sq): Move? =
        legalMoves(board, turn).firstOrNull { it.from == from && it.to == to }

    /**
     * Wire'dan («r,c>r,c>...») qonuniy yurishni tiklaydi.
     * Urilgan donalar ro‘yxati doskadan qayta hisoblanadi — mijoz yuborgan ro‘yxatga
     * ishonmaymiz (aldashning oldini oladi).
     */
    fun moveFromWire(board: String, turn: Side, raw: String?): Move? {
        val parts = raw?.trim()?.split(">") ?: return null
        if (parts.size < 2) return null
        val from = Sq.parse(parts[0]) ?: return null
        val steps = ArrayList<Sq>(parts.size - 1)
        for (i in 1 until parts.size) steps.add(Sq.parse(parts[i]) ?: return null)
        val to = steps[steps.size - 1]
        return legalMoves(board, turn).firstOrNull {
            it.from == from && it.to == to && samePath(it.path, steps)
        }
    }

    fun samePath(a: List<Sq>, b: List<Sq>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].row != b[i].row || a[i].col != b[i].col) return false
        }
        return true
    }

    private fun isVictim(cells: CharArray, sq: Sq, side: Side, captured: List<Sq>): Boolean {
        if (!isPlayable(sq.row, sq.col)) return false
        val piece = cells[sq.index]
        if (piece == EMPTY) return false
        if (sideOf(piece) == side) return false
        for (taken in captured) if (taken.row == sq.row && taken.col == sq.col) return false
        return true
    }

    private fun collectManQuiet(cells: CharArray, from: Sq, side: Side, out: MutableList<Move>) {
        for ((dr, dc) in forwardDirs(side)) {
            val to = Sq(from.row + dr, from.col + dc)
            if (!isPlayable(to.row, to.col)) continue
            if (cells[to.index] != EMPTY) continue
            out.add(
                Move(
                    from = from,
                    to = to,
                    path = listOf(to),
                    endsAsKing = to.row == promoRow(side)
                )
            )
        }
    }

    private fun collectKingQuiet(cells: CharArray, from: Sq, side: Side, out: MutableList<Move>) {
        for ((dr, dc) in DIRS) {
            var r = from.row + dr
            var c = from.col + dc
            while (isPlayable(r, c)) {
                val to = Sq(r, c)
                if (cells[to.index] != EMPTY) break
                out.add(Move(from = from, to = to, path = listOf(to), endsAsKing = true))
                r += dr
                c += dc
            }
        }
    }

    /**
     * Urish zanjirlarini DFS bilan yig‘adi. Faqat TUGALLANGAN (davomi yo‘q) zanjirlar
     * yoziladi — qisqartirilgan zanjir maksimal bo‘la olmaydi.
     *
     * [root] — zanjir boshlangan katak (rekursiyada `from` o‘zgaradi, `root` o‘zgarmaydi).
     */
    private fun collectSequences(
        cells: CharArray,
        root: Sq,
        from: Sq,
        side: Side,
        asKing: Boolean,
        captured: MutableList<Sq>,
        path: MutableList<Sq>,
        crowned: Boolean,
        out: MutableList<Move>
    ) {
        var extended = false
        for ((dr, dc) in DIRS) {
            if (asKing) {
                var r = from.row + dr
                var c = from.col + dc
                var victim: Sq? = null
                while (isPlayable(r, c)) {
                    val sq = Sq(r, c)
                    val piece = cells[sq.index]
                    if (victim == null) {
                        if (piece != EMPTY) {
                            if (!isVictim(cells, sq, side, captured)) break
                            victim = sq
                        }
                    } else {
                        if (piece != EMPTY) break
                        extended = true
                        hop(cells, root, from, sq, victim!!, side, asKing, captured, path, crowned, out)
                    }
                    r += dr
                    c += dc
                }
            } else {
                val victim = Sq(from.row + dr, from.col + dc)
                val land = Sq(from.row + dr * 2, from.col + dc * 2)
                if (!isPlayable(land.row, land.col)) continue
                if (!isVictim(cells, victim, side, captured)) continue
                if (cells[land.index] != EMPTY) continue
                extended = true
                hop(cells, root, from, land, victim, side, asKing, captured, path, crowned, out)
            }
        }
        if (!extended && captured.isNotEmpty()) {
            val last = path[path.size - 1]
            out.add(
                Move(
                    from = root,
                    to = last,
                    captures = ArrayList(captured),
                    path = ArrayList(path),
                    endsAsKing = crowned || asKing || last.row == promoRow(side)
                )
            )
        }
    }

    private fun hop(
        cells: CharArray,
        root: Sq,
        from: Sq,
        land: Sq,
        victim: Sq,
        side: Side,
        wasKing: Boolean,
        captured: MutableList<Sq>,
        path: MutableList<Sq>,
        crowned: Boolean,
        out: MutableList<Move>
    ) {
        captured.add(victim)
        path.add(land)
        val justCrowned = !wasKing && !crowned && land.row == promoRow(side)
        collectSequences(
            cells, root, land, side, wasKing || justCrowned,
            captured, path, crowned || justCrowned, out
        )
        path.removeAt(path.size - 1)
        captured.removeAt(captured.size - 1)
    }

    // --------------------------------------------------------------- apply/end

    /**
     * Yurishni qo‘llaydi. Xato (qoidabuzarlik) bo‘lsa null.
     * [idlePlies] — urishsiz/oddiy dona yurishisiz javoblar soni (durang hisobi).
     */
    fun apply(board: String, move: Move, turn: Side, idlePlies: Int): Applied? {
        if (!isValidBoard(board)) return null
        val legal = legalMoves(board, turn).firstOrNull {
            it.from == move.from && it.to == move.to && sameSquares(it.captures, move.captures)
        } ?: return null

        val cells = board.toCharArray()
        val piece = cells[legal.from.index]
        if (sideOf(piece) != turn) return null

        cells[legal.from.index] = EMPTY
        for (sq in legal.captures) cells[sq.index] = EMPTY
        cells[legal.to.index] = if (legal.endsAsKing) kingChar(turn) else piece

        val next = turn.opposite()
        val idle = if (legal.isCapture || isMan(piece)) 0 else idlePlies + 1
        val nextBoard = String(cells)
        return Applied(nextBoard, next, idle, evaluateEnd(nextBoard, next, idle))
    }

    fun evaluateEnd(board: String, turn: Side, idlePlies: Int): End? {
        if (!isValidBoard(board)) return null
        val white = count(board, Side.WHITE)
        val black = count(board, Side.BLACK)
        if (white == 0 && black == 0) return End.DRAW
        if (black == 0) return End.WHITE_WIN
        if (white == 0) return End.BLACK_WIN
        if (!hasAnyMove(board, turn)) {
            return if (turn == Side.WHITE) End.BLACK_WIN else End.WHITE_WIN
        }
        if (idlePlies >= IDLE_DRAW_PLIES) return End.DRAW
        return null
    }

    /** Oq/qora donalar soni (UI badge uchun). */
    fun material(board: String): Pair<Int, Int> =
        count(board, Side.WHITE) to count(board, Side.BLACK)

    fun sameSquares(a: List<Sq>, b: List<Sq>): Boolean {
        if (a.size != b.size) return false
        for (sq in a) {
            var found = false
            for (other in b) {
                if (other.row == sq.row && other.col == sq.col) {
                    found = true
                    break
                }
            }
            if (!found) return false
        }
        return true
    }
}
