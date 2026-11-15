package uz.kodlikino.top4.games

import kotlin.math.abs
import kotlin.random.Random

/**
 * Oflayn mashq uchun oddiy shashka boti.
 *
 * Bir yurishli baholash: urilgan donalar (dama qimmatroq), dama bo‘lish,
 * oldinga siljish va markazga intilish. Har nomzoddan keyin raqibning eng
 * katta urishi jarima sifatida ayiriladi — bot bepul dona bermaydi.
 *
 * Sof funksiya: holatni o‘zgartirmaydi, faqat qonuniy yurish qaytaradi.
 */
object CheckersBot {

    private const val WIN_SCORE = 1_000_000
    private const val MAN_VALUE = 100
    private const val KING_VALUE = 320

    fun chooseMove(
        board: String,
        side: CheckersEngine.Side,
        idlePlies: Int = 0
    ): CheckersEngine.Move? {
        val moves = CheckersEngine.legalMoves(board, side)
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moves[0]

        var bestScore = Int.MIN_VALUE
        val best = ArrayList<CheckersEngine.Move>(2)

        for (move in moves) {
            val applied = CheckersEngine.apply(board, move, side, idlePlies) ?: continue
            var score = moveScore(board, move, side)
            val end = applied.end
            if (end != null) {
                score += when (end) {
                    CheckersEngine.End.DRAW -> 0
                    CheckersEngine.End.WHITE_WIN ->
                        if (side == CheckersEngine.Side.WHITE) WIN_SCORE else -WIN_SCORE
                    CheckersEngine.End.BLACK_WIN ->
                        if (side == CheckersEngine.Side.BLACK) WIN_SCORE else -WIN_SCORE
                }
            } else {
                score += material(applied.board, side)
                val replies = CheckersEngine.legalMoves(applied.board, applied.nextTurn)
                var worstCapture = 0
                for (reply in replies) {
                    if (reply.captures.size > worstCapture) worstCapture = reply.captures.size
                }
                score -= worstCapture * 45
                score += replies.size / 2
            }
            if (score > bestScore) {
                bestScore = score
                best.clear()
                best.add(move)
            } else if (score == bestScore) {
                best.add(move)
            }
        }

        if (best.isEmpty()) return moves[0]
        return best[Random.nextInt(best.size)]
    }

    private fun moveScore(
        board: String,
        move: CheckersEngine.Move,
        side: CheckersEngine.Side
    ): Int {
        var score = move.captures.size * 110
        for (victim in move.captures) {
            if (CheckersEngine.isKing(CheckersEngine.pieceAt(board, victim))) score += 90
        }
        if (move.endsAsKing && !CheckersEngine.isKing(CheckersEngine.pieceAt(board, move.from))) {
            score += 140
        }
        val advance = if (side == CheckersEngine.Side.WHITE) 7 - move.to.row else move.to.row
        score += advance * 4
        score += 6 - abs(move.to.col * 2 - 7)
        return score
    }

    /** Qimmatbaho donalar + oddiy donalarning oldinga siljishi. */
    private fun material(board: String, side: CheckersEngine.Side): Int {
        var score = 0
        for (index in 0 until CheckersEngine.CELLS) {
            val sq = CheckersEngine.Sq.of(index)
            if (!CheckersEngine.isPlayable(sq.row, sq.col)) continue
            val piece = board[index]
            val owner = CheckersEngine.sideOf(piece) ?: continue
            val sign = if (owner == side) 1 else -1
            if (CheckersEngine.isKing(piece)) {
                score += sign * KING_VALUE
            } else {
                val advance = if (owner == CheckersEngine.Side.WHITE) 7 - sq.row else sq.row
                score += sign * (MAN_VALUE + advance * 4)
            }
        }
        return score
    }
}
