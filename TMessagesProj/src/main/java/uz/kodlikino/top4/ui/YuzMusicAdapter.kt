package uz.kodlikino.top4.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telegram.messenger.R
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #62: musiqiy natijalar ro'yxati. Qatorni bosish — qo'shiqni ilova
 * ichidagi pleyerda ijro etadi (YouTube endi ochilmaydi); download tugmasi —
 * keshga saqlash (yoki yuklanganlar ro'yxatida — o'chirish).
 *
 * YuzGram #109: "ko'proq" yuklanayotganda ro'yxat oxiriga YuzShimmer
 * skelet-qator qo'shiladi; davom etayotgan yuklashda tugma X (bekor
 * qilish) ga aylanadi va qator ostida neon gradient progress chizig'i
 * yuradi.
 */
class YuzMusicAdapter(
    private val onPlay: (YuzTrack, Int) -> Unit,
    private val onDownload: (YuzTrack, Int) -> Unit,
    // YuzGram #67: qatorni bosib turish — playlist/navbat menyusi.
    private val onLongPress: (YuzTrack, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_SHIMMER = 1
    }

    private val items = ArrayList<YuzTrack>()
    private var playingPos = -1
    private var downloadedIds: Set<Long> = emptySet()
    // YuzGram #98: yuklab olinayotgan qatorlar.
    private var downloadingIds: Set<Long> = emptySet()
    // YuzGram #104: id -> 0..100 foiz; qatorda "↓ 47%" ko'rinishida chiqadi.
    private val progressById = HashMap<Long, Int>()
    // #109: ro'yxat oxiridagi "ko'proq yuklanmoqda" skeleti.
    private var loadingMore = false

    /** #109: infinite scroll skeletini yoqish/o'chirish (asosiy IP). */
    fun setLoadingMore(on: Boolean) {
        if (loadingMore == on) return
        loadingMore = on
        if (on) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
    }

    fun setDownloading(ids: Set<Long>) {
        // Tugagan/uzilgan qatorlarning foizini ham tozalaymiz.
        progressById.keys.retainAll(ids)
        downloadingIds = ids.toSet()
        notifyDataSetChanged()
    }

    /** #104: faqat shu qatorni qayta bog'laymiz — butun ro'yxatni emas. */
    fun setProgress(id: Long, pct: Int) {
        if (id !in downloadingIds) return
        // #115: o'zgarmagan foizga qayta-bog'lash so'rovini yubormaymiz —
        // jonli yangilanish (500ms tick) endi bir xil qiymatni ham beradi.
        if (progressById[id] == pct) return
        progressById[id] = pct
        val pos = items.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun submit(list: List<YuzTrack>) {
        loadingMore = false
        items.clear()
        items.addAll(list)
        playingPos = -1
        notifyDataSetChanged()
    }

    /** #104: infinite scroll — ro'yxat oxiriga yangi qatorlar qo'shadi. */
    fun append(list: List<YuzTrack>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun setPlaying(pos: Int) {
        val old = playingPos
        playingPos = pos
        if (old in items.indices) notifyItemChanged(old)
        if (pos in items.indices) notifyItemChanged(pos)
    }

    /** Skelet hisobiga pozitsiya items chegarasidan katta bo'lishi mumkin. */
    fun playingPosition(): Int = playingPos

    fun setDownloaded(ids: Set<Long>) {
        downloadedIds = ids
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size + if (loadingMore) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position >= items.size) TYPE_SHIMMER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SHIMMER) {
            return ShimmerVh(YuzShimmer(parent.context).apply { rowCount = 1 })
        }
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_musiqa, parent, false)
        return RowVh(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ShimmerVh) return // YuzShimmer o'zi animatsiyalanadi
        val t = items.getOrNull(position) ?: return
        val h = holder as RowVh
        h.title.text = t.title
        val sub = StringBuilder()
        if (t.artist.isNotBlank()) sub.append(t.artist)
        if (t.album.isNotBlank()) {
            if (sub.isNotEmpty()) sub.append(" • ")
            sub.append(t.album)
        }
        h.artist.text = sub
        if (t.coverUrl.isNotBlank()) {
            Glide.with(h.cover).load(t.coverUrl).into(h.cover)
        } else {
            h.cover.setImageDrawable(null)
        }
        h.play.setImageResource(
            if (position == playingPos) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
        )
        val has = t.id in downloadedIds
        val busy = t.id in downloadingIds
        // #104: yuklanayotgan qatorda davomiylik o'rniga foiz chiqadi.
        // #115: yoniga taroq tezligi qo'shiladi — "↓ 47% 384K/s".
        // Foiz/tezlik reyestrdan o'qiladi: boshqa ekrandan boshlangan
        // yuklash ham shu ro'yxatda to'g'ri holatda chiziladi.
        val pct = progressById[t.id] ?: YuzMusicCache.inflightPct(t.id)
        val rate = YuzMusicCache.rateLabel(t.id)
        h.dur.text = when {
            busy -> if (rate.isEmpty()) "↓ $pct%" else "↓ $pct% $rate"
            t.durationSec <= 0 -> ""
            else -> String.format("%d:%02d", t.durationSec / 60, t.durationSec % 60)
        }
        // #109: busy holatda tugma — bekor qilish (X), qator ostida esa
        // gradient progress chizig'i foiz bo'yicha cho'ziladi.
        h.dl.setImageResource(
            if (busy) R.drawable.ic_yuz_close else R.drawable.ic_yuz_download
        )
        h.dl.alpha = if (busy || has) 1.0f else 0.55f
        h.dlBox.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) h.dlFill.scaleX = pct / 100f
        h.dl.setOnClickListener { onDownload(t, h.adapterPosition) }
        h.itemView.setOnClickListener { onPlay(t, h.adapterPosition) }
        h.itemView.setOnLongClickListener {
            onLongPress(t, h.adapterPosition)
            true
        }
    }

    class ShimmerVh(v: View) : RecyclerView.ViewHolder(v)

    class RowVh(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivMusiqaCover)
        val title: TextView = v.findViewById(R.id.tvMusiqaTitle)
        val artist: TextView = v.findViewById(R.id.tvMusiqaArtist)
        val dur: TextView = v.findViewById(R.id.tvMusiqaDur)
        val dl: ImageButton = v.findViewById(R.id.btnMusiqaDl)
        val play: ImageButton = v.findViewById(R.id.btnMusiqaPlay)
        // #109: gradient progress — chapdan o'ngga scaleX bilan o'sadi.
        val dlBox: View = v.findViewById(R.id.boxMusiqaDlProgress)
        val dlFill: View = v.findViewById(R.id.vwMusiqaDlFill)

        init {
            dlFill.pivotX = 0f
        }
    }
}
