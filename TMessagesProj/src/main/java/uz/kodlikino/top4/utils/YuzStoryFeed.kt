package uz.kodlikino.top4.utils

import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble
import org.telegram.ui.Stories.StoriesController
import uz.kodlikino.top4.models.Reel
import java.io.File

/**
 * #106 — Telegram hikoyalaridan "Reels" tasmasi.
 *
 * Eski Firebase `reels` feed'ning o'rniga: barcha ko'rishga ruxsat berilgan
 * faol hikoyalar (do'stlar, kontaktlar, kanallar, guruhlar) + o'zimizning
 * arxiv va profilga saqlangan (pinned) hikoyalar bitta vertikal tasмага
 * aylantiriladi. Statistikalar (ko'rishlar/reaksiyalar/layk holati) to'g'ridan-
 * to'g'ri Telegram'dan olinadi; izohlar Firebase'da qoladi — ular uchun
 * [Reel.id] barqaror kalit: `story_{dialogId}_{storyId}`.
 *
 * Video ijrosi: media3 pleyer `tg://` sxemasini tushunmaydi (Telegram'ning
 * o'za pleyeri vendored exoplayer2 + ExtendedDefaultDataSource bilan ishlaydi),
 * shuning uchun video [FileLoader] orqali attachment papkasiga tushiriladi va
 * lokal `file://` sifatida ijro etiladi — natija Telegram'ning o'z hikoya
 * fayli bilan bir xil (bir marta yuklangan hikoya deyarli darhol ochiladi).
 */
object YuzStoryFeed {

    const val KEY_PREFIX = "story_"

    fun storyKey(dialogId: Long, storyId: Int): String = KEY_PREFIX + dialogId + "_" + storyId

    /** "story_123_45" -> (123, 45); story kaliti bo'lmasa null. */
    fun parseKey(key: String?): Pair<Long, Int>? {
        if (key == null || !key.startsWith(KEY_PREFIX)) return null
        val parts = key.substring(KEY_PREFIX.length).split('_')
        if (parts.size != 2) return null
        val d = parts[0].toLongOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return d to s
    }

    /**
     * To'liq tasma: faol hikoyalar (har bir peer bo'yicha Telegram beradigan
     * tartibda) + (umumiy tasmasida) o'z arxivimiz va profilga saqlanganlar.
     * Ma'lumotlar allaqachon TDLib/Telegram tomonidan sinxronlangan — bu funksiya
     * faqat xotidadagi obyektlarni o'qiydi; arxiv/pinned ro'yxatlari birinchi
     * bosqichda bo'sh bo'lishi mumkin, ular kelgach `storiesListUpdated`
     * signali orqali tasma qayta quriladi.
     */
    @JvmStatic
    fun buildFeed(account: Int, filterUid: String): List<Reel> {
        val out = ArrayList<Reel>()
        val seen = HashSet<String>()
        val sc = try {
            MessagesController.getInstance(account).getStoriesController()
        } catch (_: Exception) {
            return out
        }
        try {
            sc.checkExpiredStories()
        } catch (_: Exception) {
        }
        val selfId = UserConfig.getInstance(account).clientUserId
        val filterId = filterUid.removePrefix("tg_").toLongOrNull()

        val peers = ArrayList<TL_stories.PeerStories>()
        try {
            sc.getDialogListStories()?.let { peers.addAll(it) }
            val self = sc.getStories(selfId)
            if (self != null && !peers.contains(self)) peers.add(0, self)
        } catch (_: Exception) {
        }
        for (ps in peers) {
            val dialogId = try {
                DialogObject.getPeerDialogId(ps.peer)
            } catch (_: Exception) {
                continue
            }
            if (dialogId == 0L) continue
            if (filterId != null && filterId != dialogId) continue
            // Bitta peer buzilgan bo'lsa butun tasma o'lmasin — hikoyalar
            // xotidadagi obyektlar, ular istalgan paytda almashishi mumkin.
            try {
                for (item in ps.stories) {
                    val reel = toReel(account, dialogId, item, archived = false) ?: continue
                    if (seen.add(reel.id)) out.add(reel)
                }
            } catch (_: Exception) {
            }
        }

        // O'zimizning arxiv + profilga saqlangan (pinned) hikoyalar — faqat
        // umumiy tasmada yoki o'z profilimizdan filtrlanganda.
        if (filterId == null || filterId == selfId) {
            appendList(sc, account, out, seen, selfId, StoriesController.StoriesList.TYPE_ARCHIVE)
            appendList(sc, account, out, seen, selfId, StoriesController.StoriesList.TYPE_PINNED)
        }
        return out
    }

    /** StoriesList'ni (arxiv/pinned) yuklashga undaydi va hozirgi kontentini qo'shadi. */
    private fun appendList(
        sc: StoriesController,
        account: Int,
        out: ArrayList<Reel>,
        seen: HashSet<String>,
        dialogId: Long,
        type: Int
    ) {
        try {
            val list = sc.getStoriesList(dialogId, type) ?: return
            // Idempotent: yuklanayotgan bo'lsa load() false qaytaradi, cached
            // messageObjects baribir o'qiladi.
            list.load(false, 30, null)
            for (mo in list.messageObjects) {
                val item = mo?.storyItem ?: continue
                val reel = toReel(account, dialogId, item, archived = true) ?: continue
                if (seen.add(reel.id)) out.add(reel)
            }
        } catch (_: Exception) {
        }
    }

    /** StoryItem -> Reel. Video bo'lmagan (foto) hikoyalar ham qo'llab-quvvatlanadi. */
    private fun toReel(account: Int, dialogId: Long, item: TL_stories.StoryItem, archived: Boolean): Reel? {
        val media = item.media ?: return null
        if (media is TLRPC.TL_messageMediaUnsupported) return null
        val doc = try { media.getDocument() } catch (_: Exception) { null }
        val isVideo = doc != null && FileLoader.isVideoMimeType(doc.mime_type)
        val name = peerName(account, dialogId)
        val liked = item.sent_reaction != null && item.sent_reaction !is TLRPC.TL_reactionEmpty
        val views = item.views
        return Reel(
            id = storyKey(dialogId, item.id),
            title = item.caption ?: "",
            views = views?.views_count ?: 0,
            likeCount = views?.reactions_count ?: 0,
            createdAt = item.date.toLong() * 1000L,
            uploadedByUid = "tg_$dialogId",
            uploadedByName = name,
            isUserUpload = false,
            isLikedByMe = liked,
            isStory = true,
            storyDialogId = dialogId,
            storyId = item.id,
            storyIsVideo = isVideo,
            storyArchived = archived
        )
    }

    private fun peerName(account: Int, dialogId: Long): String {
        return try {
            val mc = MessagesController.getInstance(account)
            if (dialogId > 0) {
                UserObject.getUserName(mc.getUser(dialogId)) ?: ""
            } else {
                mc.getChat(-dialogId)?.title ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Kalit bo'yicha StoryItem'ni topadi: avval faol hikoyalar, so'ng o'z
     * arxiv/pinned ro'yxatlari. Topilmasa null — hikoya endi ko'rinmaydi.
     */
    @JvmStatic
    fun findStoryItem(account: Int, dialogId: Long, storyId: Int): TL_stories.StoryItem? {
        val sc = try {
            MessagesController.getInstance(account).getStoriesController()
        } catch (_: Exception) {
            return null
        }
        try {
            sc.getStories(dialogId)?.let { ps ->
                for (item in ps.stories) {
                    if (item.id == storyId) {
                        item.dialogId = dialogId
                        return item
                    }
                }
            }
        } catch (_: Exception) {
        }
        for (type in intArrayOf(
            StoriesController.StoriesList.TYPE_ARCHIVE,
            StoriesController.StoriesList.TYPE_PINNED
        )) {
            try {
                val list = sc.getStoriesList(dialogId, type) ?: continue
                for (mo in list.messageObjects) {
                    val item = mo?.storyItem ?: continue
                    if (item.id == storyId) {
                        item.dialogId = dialogId
                        return item
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Layk/unlike — Telegram reaksiyasi (`❤` emoji). Muvaffaqiyatda true. */
    @JvmStatic
    fun likeStory(account: Int, dialogId: Long, storyId: Int, liked: Boolean): Boolean {
        val item = findStoryItem(account, dialogId, storyId) ?: return false
        return try {
            val vr = if (liked) {
                ReactionsLayoutInBubble.VisibleReaction().apply { emojicon = "\u2764\ufe0f" }
            } else {
                null
            }
            MessagesController.getInstance(account).getStoriesController().setStoryReaction(dialogId, item, vr)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * "Ko'rilgan" belgisi — Telegram'ning o'z max_read_id hisobiga yoziladi
     * (eski loyihadagi Firestore `views` increment o'rniga). Faol hikoyalar
     * uchun ishlaydi; arxivdagilari hisobga olinmaydi (mavjud API yo'q).
     */
    @JvmStatic
    fun markViewed(account: Int, dialogId: Long, storyId: Int): Boolean {
        val item = findStoryItem(account, dialogId, storyId) ?: return false
        return try {
            val sc = MessagesController.getInstance(account).getStoriesController()
            val ps = sc.getStories(dialogId) ?: return false
            sc.markStoryAsRead(ps, item, false)
        } catch (_: Exception) {
            false
        }
    }

    // ============================ Video ijrosi ============================

    /**
     * Hikoya videosining lokal faylini qaytaradi (tayyor bo'lsa).
     * [FileLoader.getPathToAttach] bir xil nom/yo'lni hisoblaydi — yuklash
     * tugagan-fayl o'lchami document.size ga teng bo'ladi.
     */
    @JvmStatic
    fun resolveVideoFile(account: Int, reel: Reel): File? {
        val doc = storyDocument(account, reel) ?: return null
        // StoryItem.attachPath — Telegram o'zi saqlaydigan tez yo'l.
        val item = findStoryItem(account, reel.storyDialogId, reel.storyId)
        item?.attachPath?.let { p ->
            try {
                val f = File(p)
                if (f.exists() && f.length() > 0) return f
            } catch (_: Exception) {
            }
        }
        return try {
            val f = FileLoader.getInstance(account).getPathToAttach(doc) ?: return null
            if (f.exists() && (doc.size == 0L || f.length() == doc.size)) f else null
        } catch (_: Exception) {
            null
        }
    }

    /** Yuklashni boshlaydi (yoki davom ettiradi — idempotent). */
    @JvmStatic
    fun startVideoDownload(account: Int, reel: Reel, high: Boolean): Boolean {
        val doc = storyDocument(account, reel) ?: return false
        val item = findStoryItem(account, reel.storyDialogId, reel.storyId) ?: return false
        return try {
            val fl = FileLoader.getInstance(account)
            if (item.fileReference == 0) {
                item.fileReference = fl.getFileReference(item)
            }
            fl.loadFile(
                doc, item,
                if (high) FileLoader.PRIORITY_HIGH else FileLoader.PRIORITY_LOW,
                0
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Qo'shni sahifalarni diskka oldindan tortish (keyingi o'tish lagsiz bo'lsin). */
    @JvmStatic
    fun preload(account: Int, reel: Reel) {
        if (!reel.isStory || !reel.storyIsVideo) return
        if (resolveVideoFile(account, reel) != null) return
        startVideoDownload(account, reel, high = false)
    }

    private fun storyDocument(account: Int, reel: Reel): TLRPC.Document? {
        if (!reel.isStory || !reel.storyIsVideo) return null
        val item = findStoryItem(account, reel.storyDialogId, reel.storyId) ?: return null
        return try {
            item.media?.getDocument()
        } catch (_: Exception) {
            null
        }
    }

    /** Reel kaliti story bo'lsa — (dialogId, storyId); aks holda null. */
    @JvmStatic
    fun keyOf(reel: Reel): Pair<Long, Int>? =
        if (reel.isStory) reel.storyDialogId to reel.storyId else parseKey(reel.id)
}
