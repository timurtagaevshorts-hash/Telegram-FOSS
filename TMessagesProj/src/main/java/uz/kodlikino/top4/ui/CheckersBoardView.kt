package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.telegram.messenger.R
import uz.kodlikino.top4.games.CheckersEngine
import kotlin.math.min

/**
 * Dama taxtasi — Canvas'da chiziladi (har katak uchun View yaratmaydi).
 *
 * Yurish mantiqiy jihatdan BITTA zanjir bo‘lib yuboriladi (`r,c>r,c>...`), ammo
 * foydalanuvchi uni bosqichma-bosqich bosadi: har bosishda qolgan qonuniy
 * zanjirlar ichidan prefiks mosligi tekshiriladi. Zanjir tugagach (boshqa
 * davomi yo‘q) yurish avtomatik yuboriladi — shunda server tomonidagi bitta
 * tranzaksiya holatni buzmasdan yangilanadi.
 */
class CheckersBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onMove: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val squareDark = context.getColor(R.color.yuz_games_board_dark)
    private val squareLight = context.getColor(R.color.yuz_games_board_light)
    private val pieceWhite = context.getColor(R.color.yuz_games_piece_white)
    private val pieceWhiteEdge = context.getColor(R.color.yuz_games_piece_white_edge)
    private val pieceBlack = context.getColor(R.color.yuz_games_piece_black)
    private val pieceBlackEdge = context.getColor(R.color.yuz_games_piece_black_edge)
    private val kingGold = context.getColor(R.color.yuz_games_king)
    private val accent = context.getColor(R.color.yuz_action)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
    }
    private val crownPath = Path()

    private var board: String = CheckersEngine.initialBoard()
    private var mySide: CheckersEngine.Side? = null
    private var turn: CheckersEngine.Side = CheckersEngine.Side.WHITE
    private var lastFrom = ""
    private var lastTo = ""
    private var interactive = false

    private var origin: CheckersEngine.Sq? = null
    private var chain = ArrayList<CheckersEngine.Sq>(4)
    private var legal: List<CheckersEngine.Move> = emptyList()
    private var candidates: List<CheckersEngine.Move> = emptyList()

    private var side = 0

    fun setPosition(
        board: String,
        mySide: CheckersEngine.Side?,
        turn: CheckersEngine.Side,
        lastFrom: String,
        lastTo: String,
        interactive: Boolean
    ) {
        val boardChanged = board != this.board
        val sideChanged = mySide != this.mySide
        this.board = if (CheckersEngine.isValidBoard(board)) board else CheckersEngine.initialBoard()
        this.mySide = mySide
        this.turn = turn
        this.lastFrom = lastFrom
        this.lastTo = lastTo
        this.interactive = interactive && mySide != null && mySide == turn
        if (boardChanged || sideChanged) {
            origin = null
            chain.clear()
            candidates = emptyList()
        }
        legal = if (this.interactive && this.mySide != null) {
            CheckersEngine.legalMoves(this.board, this.mySide!!)
        } else {
            emptyList()
        }
        invalidate()
    }

    fun clearSelection() {
        origin = null
        chain.clear()
        candidates = emptyList()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val sidePx = when (mode) {
            MeasureSpec.UNSPECIFIED -> w
            else -> min(w, (h * 0.62f).toInt())
        }
        side = sidePx.coerceAtLeast((160 * density).toInt())
        setMeasuredDimension(w, side)
    }

    override fun onDraw(canvas: Canvas) {
        val boardSide = side.toFloat()
        if (boardSide <= 0f) return
        val ox = (width - boardSide) / 2f
        val cell = boardSide / CheckersEngine.SIZE

        val frame = RectF(ox, 0f, ox + boardSide, boardSide)
        val radius = 14f * density
        val clip = Path().apply { addRoundRect(frame, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)

        // Kataklar
        for (r in 0 until CheckersEngine.SIZE) {
            for (c in 0 until CheckersEngine.SIZE) {
                val (dx, dy) = displayCell(r, c)
                fillPaint.color = if (CheckersEngine.isPlayable(r, c)) squareLight else squareDark
                canvas.drawRect(
                    ox + dx * cell, dy * cell,
                    ox + (dx + 1) * cell, (dy + 1) * cell,
                    fillPaint
                )
            }
        }

        // Oxirgi yurish izlari
        highlightSquare(canvas, ox, cell, lastFrom, 0x332AABEE)
        highlightSquare(canvas, ox, cell, lastTo, 0x552AABEE)

        // Tanlangan dona va zanjir yo‘li
        origin?.let { highlightSquare(canvas, ox, cell, it.key(), 0x332AABEE) }
        for (step in chain) highlightSquare(canvas, ox, cell, step.key(), 0x222AABEE)

        // Qonuniy nishonlar
        if (interactive && origin != null) {
            val prefix = chain.toList()
            val targets = LinkedHashSet<CheckersEngine.Sq>()
            var captureTarget = false
            for (move in candidates) {
                if (move.path.size <= prefix.size) continue
                if (!pathPrefix(move.path, prefix)) continue
                targets.add(move.path[prefix.size])
                if (move.isCapture) captureTarget = true
            }
            for (target in targets) {
                val (dx, dy) = displayCell(target.row, target.col)
                val cx = ox + (dx + 0.5f) * cell
                val cy = (dy + 0.5f) * cell
                if (captureTarget) {
                    strokePaint.color = accent
                    strokePaint.strokeWidth = 2f * density
                    canvas.drawCircle(cx, cy, cell * 0.19f, strokePaint)
                } else {
                    fillPaint.color = 0x992AABEE.toInt()
                    canvas.drawCircle(cx, cy, cell * 0.10f, fillPaint)
                }
            }
            strokePaint.color = accent
            strokePaint.strokeWidth = 2f * density
            origin?.let {
                val (dx, dy) = displayCell(it.row, it.col)
                canvas.drawRect(
                    ox + dx * cell + 1f, dy * cell + 1f,
                    ox + (dx + 1) * cell - 1f, (dy + 1) * cell - 1f,
                    strokePaint
                )
            }
        }

        // Donalar
        for (r in 0 until CheckersEngine.SIZE) {
            for (c in 0 until CheckersEngine.SIZE) {
                if (!CheckersEngine.isPlayable(r, c)) continue
                val piece = board[r * CheckersEngine.SIZE + c]
                if (piece == CheckersEngine.EMPTY) continue
                val (dx, dy) = displayCell(r, c)
                drawPiece(canvas, ox + (dx + 0.5f) * cell, (dy + 0.5f) * cell, cell, piece)
            }
        }

        canvas.restore()

        strokePaint.color = 0xFF202B3C.toInt()
        strokePaint.strokeWidth = 1f * density
        canvas.drawRoundRect(frame, radius, radius, strokePaint)
    }

    private fun drawPiece(canvas: Canvas, cx: Float, cy: Float, cell: Float, piece: Char) {
        val white = piece == CheckersEngine.WHITE_MAN || piece == CheckersEngine.WHITE_KING
        val king = CheckersEngine.isKing(piece)
        val radius = cell * 0.37f

        canvas.drawCircle(cx + 1.2f * density, cy + 1.8f * density, radius, shadowPaint)

        fillPaint.color = if (white) pieceWhite else pieceBlack
        canvas.drawCircle(cx, cy, radius, fillPaint)

        strokePaint.color = if (white) pieceWhiteEdge else pieceBlackEdge
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, radius, strokePaint)

        strokePaint.color = if (white) 0x33121008 else 0x55FFFFFF
        strokePaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, radius * 0.66f, strokePaint)

        if (king) {
            drawCrown(canvas, cx, cy, radius)
        } else {
            fillPaint.color = if (white) 0x22121008 else 0x22FFFFFF
            canvas.drawCircle(cx, cy, radius * 0.28f, fillPaint)
        }
    }

    private fun drawCrown(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val w = radius * 1.15f
        val h = radius * 0.95f
        crownPath.reset()
        crownPath.moveTo(cx - w / 2f, cy + h / 2f)
        crownPath.lineTo(cx - w / 2f, cy - h / 2f)
        crownPath.lineTo(cx - w / 4f, cy)
        crownPath.lineTo(cx, cy - h * 0.72f)
        crownPath.lineTo(cx + w / 4f, cy)
        crownPath.lineTo(cx + w / 2f, cy - h / 2f)
        crownPath.lineTo(cx + w / 2f, cy + h / 2f)
        crownPath.close()
        fillPaint.color = kingGold
        canvas.drawPath(crownPath, fillPaint)
    }

    private fun highlightSquare(canvas: Canvas, ox: Float, cell: Float, key: String, color: Int) {
        val square = CheckersEngine.Sq.parse(key) ?: return
        val (dx, dy) = displayCell(square.row, square.col)
        fillPaint.color = color
        canvas.drawRect(
            ox + dx * cell, dy * cell,
            ox + (dx + 1) * cell, (dy + 1) * cell,
            fillPaint
        )
    }

    private fun displayCell(row: Int, col: Int): Pair<Float, Float> {
        val p = if (mySide == CheckersEngine.Side.BLACK) 7 - row else row
        val q = if (mySide == CheckersEngine.Side.BLACK) 7 - col else col
        return p.toFloat() to q.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val square = squareAt(event.x, event.y) ?: return true
                handleTap(square)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun squareAt(x: Float, y: Float): CheckersEngine.Sq? {
        val boardSide = side.toFloat()
        if (boardSide <= 0f) return null
        val ox = (width - boardSide) / 2f
        if (x < ox || x >= ox + boardSide || y < 0f || y >= boardSide) return null
        val cell = boardSide / CheckersEngine.SIZE
        val col = ((x - ox) / cell).toInt().coerceIn(0, CheckersEngine.SIZE - 1)
        val row = (y / cell).toInt().coerceIn(0, CheckersEngine.SIZE - 1)
        val r = if (mySide == CheckersEngine.Side.BLACK) 7 - row else row
        val c = if (mySide == CheckersEngine.Side.BLACK) 7 - col else col
        return CheckersEngine.Sq(r, c)
    }

    private fun handleTap(square: CheckersEngine.Sq) {
        val sideNow = mySide ?: return
        val current = origin
        if (current == null) {
            selectPiece(square, sideNow)
            return
        }
        if (square == current) {
            clearSelection()
            return
        }
        val nextPath = chain + square
        val matches = candidates.filter { pathPrefix(it.path, nextPath) }
        if (matches.isEmpty()) {
            if (chain.isEmpty()) selectPiece(square, sideNow)
            return
        }
        val complete = matches.firstOrNull { it.path.size == nextPath.size }
        if (complete != null) {
            origin = null
            chain.clear()
            candidates = emptyList()
            invalidate()
            onMove?.invoke(complete.wire())
        } else {
            chain.add(square)
            invalidate()
        }
    }

    private fun selectPiece(square: CheckersEngine.Sq, sideNow: CheckersEngine.Side) {
        val piece = CheckersEngine.pieceAt(board, square)
        if (!CheckersEngine.belongsTo(piece, sideNow)) return
        val fromMoves = legal.filter { it.from == square }
        if (fromMoves.isEmpty()) return
        origin = square
        chain.clear()
        candidates = fromMoves
        invalidate()
    }

    private fun pathPrefix(path: List<CheckersEngine.Sq>, prefix: List<CheckersEngine.Sq>): Boolean {
        if (prefix.size > path.size) return false
        for (i in prefix.indices) {
            val a = prefix[i]
            val b = path[i]
            if (a.row != b.row || a.col != b.col) return false
        }
        return true
    }

    init {
        isClickable = true
        setWillNotDraw(false)
    }
}
