package uz.kodlikino.top4.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.utils.YuzStoryFeed

/** Reels tepasidagi hikoya doiralaridan biri (Telegram uslubi). */
data class YuzStoryCircle(
    val dialogId: Long,
    val name: String,
    val unseen: Boolean,
    val isSelf: Boolean,
    val hasStories: Boolean
)

/**
 * Telegram'ning asosiy ekranidagi kabi gorizontal hikoya doiralari:
 * birinchi bo'lib o'z profilimiz (yangi hikoya uchun "+" belgisi bilan),
 * keyin yaqinda hikoya joylagan boshqa profil/kanallar. Halqa ranglari
 * holatni bildiradi — ko'rilmagan hikoya yorqin (yuz_action), ko'rilgani
 * xira. Bosilganda Telegram'ning o'z StoryViewer'i ochiladi.
 */
class YuzStoryStripAdapter(
    private val context: Context,
    private val account: Int,
    private val onSelfAvatar: (hasStories: Boolean) -> Unit,
    private val onAddStory: () -> Unit,
    private val onPeer: (dialogId: Long) -> Unit
) : RecyclerView.Adapter<YuzStoryStripAdapter.Holder>() {

    private val items = ArrayList<YuzStoryCircle>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<YuzStoryCircle>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun hasStories(): Boolean = items.any { !it.isSelf }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.dialogId ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_story_circle, parent, false)
        val holder = Holder(view)
        val avatar = BackupImageView(parent.context)
        avatar.setRoundRadius(AndroidUtilities.dp(30f))
        holder.avatarHost.addView(
            avatar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        holder.avatar = avatar
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val circle = items.getOrNull(position) ?: return
        val self = circle.isSelf
        holder.name.text = if (self) context.getString(R.string.reels_story_you) else circle.name
        holder.addBadge.visibility = if (self) View.VISIBLE else View.GONE
        val ringColor = ContextCompat.getColor(
            context,
            if (circle.unseen) R.color.yuz_action else R.color.yuz_text_secondary
        )
        holder.ring.backgroundTintList = ColorStateList.valueOf(ringColor)

        val avatar = holder.avatar ?: return
        try {
            avatar.clearImage()
            avatar.imageReceiver.setCurrentAccount(account)
            val obj = YuzStoryFeed.peerObject(account, circle.dialogId)
                ?: UserConfig.getInstance(account).currentUser
                ?: return
            val drawable = when (obj) {
                is org.telegram.tgnet.TLRPC.User -> AvatarDrawable(obj)
                is org.telegram.tgnet.TLRPC.Chat -> AvatarDrawable(obj)
                else -> AvatarDrawable()
            }
            avatar.setForUserOrChat(obj, drawable)
        } catch (_: Exception) {
            avatar.clearImage()
        }

        holder.addBadge.setOnClickListener { onAddStory() }
        holder.itemView.setOnClickListener {
            if (self) {
                if (circle.hasStories) onSelfAvatar(true) else onAddStory()
            } else {
                onPeer(circle.dialogId)
            }
        }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ring: View = itemView.findViewById(R.id.storyRing)
        val avatarHost: FrameLayout = itemView.findViewById(R.id.storyAvatarHost)
        val addBadge: ImageView = itemView.findViewById(R.id.storyAddBadge)
        val name: TextView = itemView.findViewById(R.id.tvStoryCircleName)
        var avatar: BackupImageView? = null
    }
}
