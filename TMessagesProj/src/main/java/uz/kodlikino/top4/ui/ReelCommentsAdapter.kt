package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.models.ReelComment
import uz.kodlikino.top4.utils.YuzStoryFeed
import java.util.Locale

/**
 * Reel izohlari ro'yxati adapter'i ([ReelsActivity] ichidagi pastki panel uchun).
 *
 * KinoTOP4'dagi `ReelCommentsAdapter` xulqiyati saqlandi:
 *  - avtor nom, matn, nisbiy vaqt;
 *  - javob izohlarida "… javob: @name" ko'rsatiladi;
 *  - izohni bosish (yoki uzun bosish) javob berish rejimini yoqadi.
 *
 * #76 — daraxt ko'rinishi: javoblar o'z ASOSIY izohi ostiga chekinib
 * joylashadi (flat-thread: reply doim root'ga bog'langan bo'ladi), shuning
 * uchun o'qish tartibi tabiiy suhbat kabi.
 *
 * Telegram'ning ichida (AAR emas, manba kodi) ishlaydigan yagona RecyclerView
 * versiyasi saqlanadi — shuning uchun faqat `adapterPosition` kabi eski API'lar
 * ishlatiladi (`getBindingAdapterPosition()` bu nusxada yo'q).
 */
class ReelCommentsAdapter(
    private val appContext: Context,
    private val account: Int,
    private val onReply: (ReelComment) -> Unit,
    /** Izoh muallifi (avatar/ism) bosilganda — uning YuzGram profili. */
    private val onOpenProfile: (ReelComment) -> Unit = {}
) : RecyclerView.Adapter<ReelCommentsAdapter.CommentHolder>() {

    private val items = ArrayList<ReelComment>()

    /** Javob (child) izoh id'lari — chetga surish uchun. */
    private val childIds = HashSet<String>()

    fun submit(list: List<ReelComment>) {
        items.clear()
        items.addAll(groupByRoot(list))
        notifyDataSetChanged()
    }

    /** Root'lar ASC + har root ostida uning javoblari (kelish tartibida). */
    private fun groupByRoot(list: List<ReelComment>): List<ReelComment> {
        childIds.clear()
        val ids = list.mapTo(HashSet()) { it.id }
        val roots = ArrayList<ReelComment>()
        val kidsByRoot = HashMap<String, ArrayList<ReelComment>>()
        for (c in list) {
            val rootId = c.replyToCommentId
            if (c.isReply && rootId.isNotBlank() && ids.contains(rootId)) {
                kidsByRoot.getOrPut(rootId) { ArrayList() }.add(c)
                childIds.add(c.id)
            } else {
                // replyToCommentId yo'qolgan root'ga ishora qilsa — oddiy izoh.
                roots.add(c)
            }
        }
        val out = ArrayList<ReelComment>(list.size)
        for (r in roots) {
            out.add(r)
            kidsByRoot.remove(r.id)?.let { out.addAll(it) }
        }
        // root'i topilmagan beva javoblar oxiriga (amalda bo'lmaydi).
        kidsByRoot.values.forEach { out.addAll(it) }
        return out
    }

    /** Yangi yozilgan izohni to'g'ri o'ringa qo'yadi (javob — root ostiga). */
    fun append(comment: ReelComment) {
        if (items.any { it.id == comment.id }) return
        if (comment.isReply) {
            val rootIdx = items.indexOfFirst { it.id == comment.replyToCommentId }
            if (rootIdx >= 0) {
                var idx = rootIdx
                while (idx + 1 < items.size &&
                    items[idx + 1].replyToCommentId == comment.replyToCommentId
                ) {
                    idx++
                }
                items.add(idx + 1, comment)
                childIds.add(comment.id)
                notifyItemInserted(idx + 1)
                return
            }
        }
        items.add(comment)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        childIds.clear()
        notifyDataSetChanged()
    }

    fun lastId(): String = items.lastOrNull()?.id.orEmpty()

    /** Yangi append qilingan izohning pozitsiyasi (scroll uchun). */
    fun positionOf(id: String): Int = items.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reel_comment, parent, false)
        return CommentHolder(view)
    }

    override fun onBindViewHolder(holder: CommentHolder, position: Int) {
        items.getOrNull(position)?.let(holder::bind)
    }

    inner class CommentHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarHost: FrameLayout = itemView.findViewById(R.id.commentAvatarWrap)
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvCommentAvatar)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivCommentAvatar)
        // Telegram avatari: XML'da yaratilmaydi (faqat Context konstruktori),
        // ImageReceiver'ga akkaunt berilmasa rasm yuklanmaydi.
        private val tgAvatar = BackupImageView(itemView.context)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvCommentAuthor)
        private val tvText: TextView = itemView.findViewById(R.id.tvCommentText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvCommentTime)
        private val tvReplyHint: TextView = itemView.findViewById(R.id.tvCommentReplyHint)

        init {
            tgAvatar.setRoundRadius(AndroidUtilities.dp(15f))
            tgAvatar.getImageReceiver().setCurrentAccount(account)
            tgAvatar.visibility = View.GONE
            avatarHost.addView(
                tgAvatar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        fun bind(comment: ReelComment) {
            // Javoblar root ostiga chekinib ko'rinadi (daraxt vizuali).
            val isChild = childIds.contains(comment.id)
            val padStart = AndroidUtilities.dp(if (isChild) 34f else 14f)
            itemView.setPadding(padStart, AndroidUtilities.dp(9f), AndroidUtilities.dp(14f), AndroidUtilities.dp(9f))

            val name = comment.authorName.ifBlank { "YuzGram" }
            tvAuthor.text = name
            tvText.text = comment.text
            tvTime.text = relativeTime(comment.createdAt)
            tvAvatar.text = name.take(1).uppercase(Locale.getDefault())

            // Avatar: Telegram rasmi -> YuzGram rasm -> bosh harf.
            val dialogId = comment.userId.removePrefix("tg_").toLongOrNull() ?: 0L
            val pair = if (dialogId != 0L) YuzStoryFeed.avatarFor(account, dialogId) else null
            val photo = comment.authorPhotoUrl
            if (pair != null) {
                tgAvatar.visibility = View.VISIBLE
                tgAvatar.setForUserOrChat(pair.first, pair.second)
                ivAvatar.visibility = View.GONE
                ivAvatar.setImageDrawable(null)
                tvAvatar.visibility = View.GONE
            } else {
                tgAvatar.visibility = View.GONE
                tgAvatar.clearImage()
                if (photo.isNotBlank()) {
                    ivAvatar.visibility = View.VISIBLE
                    tvAvatar.visibility = View.GONE
                    Glide.with(ivAvatar)
                        .load(photo)
                        .apply(RequestOptions.circleCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .error(R.drawable.bg_classified_image_placeholder)
                        .into(ivAvatar)
                } else {
                    ivAvatar.visibility = View.GONE
                    ivAvatar.setImageDrawable(null)
                    tvAvatar.visibility = View.VISIBLE
                }
            }

            if (comment.isReply) {
                tvReplyHint.visibility = View.VISIBLE
                tvReplyHint.text = appContext.getString(
                    R.string.reels_comment_reply_to,
                    comment.replyToAuthorName.ifBlank { appContext.getString(R.string.AppName) }
                )
                tvReplyHint.setIconStart(R.drawable.ic_yuz_reply, sizeDp = 13, gapDp = 5)
            } else {
                tvReplyHint.visibility = View.GONE
            }

            val reply = View.OnClickListener { onReply(comment) }
            itemView.setOnClickListener(reply)
            itemView.setOnLongClickListener {
                onReply(comment)
                true
            }

            // Muallif avatar/ismi — bosilsa o'sha foydalanuvchining profili ochiladi
            // (izoh YuzGram sessiyasida yozilgani uchun userId aniq `tg_<id>`).
            val openProfile = View.OnClickListener {
                if (comment.userId.isNotBlank()) onOpenProfile(comment)
            }
            tvAuthor.setOnClickListener(openProfile)
            tvAvatar.setOnClickListener(openProfile)
            ivAvatar.setOnClickListener(openProfile)
            tgAvatar.setOnClickListener(openProfile)
            avatarHost.setOnClickListener(openProfile)
        }

        /** Nisbiy vaqt — eski loyihadagi kabi o'zbekcha ("5 daqiqa oldin"). */
        private fun relativeTime(millis: Long): String {
            if (millis <= 0L) return appContext.getString(R.string.reels_comment_now)
            val diff = System.currentTimeMillis() - millis
            if (diff < 0L) return appContext.getString(R.string.reels_comment_now)
            val minutes = diff / 60_000L
            val hours = diff / 3_600_000L
            val days = diff / 86_400_000L
            return when {
                minutes < 1 -> appContext.getString(R.string.reels_comment_now)
                minutes < 60 -> appContext.getString(R.string.reels_comment_minutes, minutes.toInt())
                hours < 24 -> appContext.getString(R.string.reels_comment_hours, hours.toInt())
                days < 30 -> appContext.getString(R.string.reels_comment_days, days.toInt())
                else -> appContext.getString(R.string.reels_comment_months, (days / 30L).toInt())
            }
        }
    }
}
