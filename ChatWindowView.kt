package com.selim.superbubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.min

class MessageAdapter(
    private val service: BubbleService,
    private val repository: ChatRepository,
    private val onPhotoClick: (Bitmap) -> Unit,
    private val onAudioClick: (String?) -> Unit,
    private val onOpenAppClick: () -> Unit
) : ListAdapter<ChatMessage, MessageAdapter.MsgViewHolder>(DIFF_CALLBACK) {

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class MsgViewHolder(
        val rootRow: LinearLayout,
        val senderHeaderTxt: TextView,
        val bubbleBox: LinearLayout,
        val photoView: ImageView,
        val msgTxt: TextView,
        val actionBtn: TextView,
        val timeTxt: TextView
    ) : RecyclerView.ViewHolder(rootRow)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(5) }
        }

        val bubbleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        val senderHeaderTxt = TextView(ctx).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#53BDEB"))
            setPadding(0, 0, 0, dpToPx(3))
            visibility = View.GONE
        }

        val photoView = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(190), dpToPx(130)).apply {
                bottomMargin = dpToPx(6)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        val msgTxt = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxWidth = dpToPx(245)
        }

        val actionBtn = TextView(ctx).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
            visibility = View.GONE
        }

        val timeTxt = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#8A8D91"))
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), 0)
        }

        bubbleBox.addView(senderHeaderTxt)
        bubbleBox.addView(photoView)
        bubbleBox.addView(msgTxt)
        bubbleBox.addView(actionBtn)
        row.addView(bubbleBox)
        row.addView(timeTxt)

        return MsgViewHolder(row, senderHeaderTxt, bubbleBox, photoView, msgTxt, actionBtn, timeTxt)
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = getItem(position)
        holder.rootRow.gravity = if (msg.isMe) Gravity.END else Gravity.START

        holder.bubbleBox.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (msg.isMe) "#0084FF" else "#303030"))
            cornerRadius = dpToPx(18).toFloat()
        }

        // Grup sohbetinde gönderen kişinin adı
        if (!msg.isMe && !msg.senderName.isNullOrBlank()) {
            holder.senderHeaderTxt.text = msg.senderName
            holder.senderHeaderTxt.visibility = View.VISIBLE
        } else {
            holder.senderHeaderTxt.visibility = View.GONE
        }

        holder.msgTxt.text = msg.text

        // ✓ = mesaj uygulamaya (WhatsApp / Instagram / Telegram) teslim edildi.
        // Karşı tarafa ulaştı / okundu bilgisi bildirimden ALINAMAZ; o yüzden çift tik (✓✓) göstermiyoruz,
        // yoksa "iletildi" gibi yanlış bir izlenim verir.
        holder.timeTxt.text = if (msg.isMe) "${msg.time} ✓" else msg.time

        // Fotoğraf varsa arka planda yükle
        holder.photoView.visibility = View.GONE
        holder.photoView.tag = msg.imagePath
        if (!msg.imagePath.isNullOrBlank()) {
            val targetPath = msg.imagePath
            repository.ioExecutor.execute {
                val bmp = repository.loadBitmapFromFile(targetPath)
                if (bmp != null) {
                    service.mainHandler.post {
                        if (holder.photoView.tag == targetPath) {
                            holder.photoView.setImageBitmap(bmp)
                            holder.photoView.visibility = View.VISIBLE
                            holder.photoView.setOnClickListener { onPhotoClick(bmp) }
                        }
                    }
                }
            }
        }

        // Özel Aksiyon Butonu (Ses / Reels / Fotoğrafı Uygulamada Gör)
        val lower = msg.text.lowercase(Locale.getDefault())
        when {
            msg.audioUri != null || lower.contains("🎤") || lower.contains("sesli mesaj") || lower.contains("voice message") -> {
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.text = if (msg.audioUri != null) "▶️ Ses Kaydını Dinle" else "🎤 Ses Kaydını Aç ↗"
                holder.actionBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00A884"))
                    cornerRadius = dpToPx(14).toFloat()
                }
                holder.actionBtn.setOnClickListener { onAudioClick(msg.audioUri) }
            }
            lower.contains("reel") || lower.contains("video gönderdi") || lower.contains("bir gönderi paylaştı") -> {
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.text = "🎬 Reels / Videoyu İzle ↗"
                holder.actionBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E1306C"))
                    cornerRadius = dpToPx(14).toFloat()
                }
                holder.actionBtn.setOnClickListener { onOpenAppClick() }
            }
            !msg.isMe && msg.imagePath == null && NotificationParser.isPhotoMessageText(msg.text) -> {
                holder.actionBtn.visibility = View.VISIBLE
                holder.actionBtn.text = "📷 Fotoğrafı Gör ↗"
                holder.actionBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0084FF"))
                    cornerRadius = dpToPx(14).toFloat()
                }
                holder.actionBtn.setOnClickListener { onOpenAppClick() }
            }
            else -> {
                holder.actionBtn.visibility = View.GONE
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}

class ChatWindowHolder(
    private val service: BubbleService,
    private val repository: ChatRepository
) {
    var rootOverlay: FrameLayout? = null
    var topHeadsContainer: LinearLayout? = null
    var incomingCallBanner: TextView? = null
    var headerAvatarImg: ImageView? = null
    var headerInitialsTxt: TextView? = null
    var headerNameTxt: TextView? = null
    var headerSubTxt: TextView? = null
    var muteBtn: TextView? = null
    var messagesRecycler: RecyclerView? = null
    var messageAdapter: MessageAdapter? = null
    var extraToolPanel: FrameLayout? = null
    var replyEdit: EditText? = null
    var sendCircleBtn: TextView? = null
    var fullScreenImageOverlay: FrameLayout? = null

    fun build(
        onCollapse: () -> Unit,
        onCloseAll: () -> Unit,
        onClearHistory: () -> Unit,
        onMuteToggle: () -> Unit,
        onVoiceCall: () -> Unit,
        onVideoCall: () -> Unit,
        onOpenApp: () -> Unit,
        onSendMessage: () -> Unit,
        onAnswerIncomingCall: () -> Unit,
        onPlayAudio: (String?) -> Unit,
        onSharePhoto: (Uri) -> Unit,
        onStartVoiceDictation: () -> Unit
    ): FrameLayout {
        val metrics = service.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels

        val overlay = object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (fullScreenImageOverlay?.visibility == View.VISIBLE) {
                        fullScreenImageOverlay?.visibility = View.GONE
                        return true
                    }
                    if (extraToolPanel?.visibility == View.VISIBLE) {
                        extraToolPanel?.visibility = View.GONE
                        return true
                    }
                    onCollapse()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setOnClickListener { onCollapse() }
        }

        val cardWidth = if (isLandscape) {
            min(dpToPx(460), (metrics.widthPixels * 0.65f).toInt())
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }

        val cardHeight = if (isLandscape) {
            (metrics.heightPixels * 0.72f).toInt()
        } else {
            (metrics.heightPixels * 0.45f).toInt()
        }

        val mainColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = if (isLandscape) dpToPx(6) else dpToPx(16)
                if (!isLandscape) {
                    leftMargin = dpToPx(12)
                    rightMargin = dpToPx(12)
                }
            }
            isClickable = true
        }

        // ÜST KAFA BARI
        val topBar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) dpToPx(44) else dpToPx(50)
            ).apply { bottomMargin = dpToPx(6) }
        }

        topHeadsContainer = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizePill = TextView(service).apply {
            text = "Küçült ⬇"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(18).toFloat()
            }
            setOnClickListener { onCollapse() }
        }

        val closeAllPill = TextView(service).apply {
            text = "✕"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val btnSize = if (isLandscape) dpToPx(32) else dpToPx(36)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener { onCloseAll() }
        }

        topBar.addView(topHeadsContainer)
        topBar.addView(minimizePill)
        topBar.addView(closeAllPill)

        // SOHBET KARTI
        val chatCard = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                cardHeight
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18191A"))
                cornerRadius = dpToPx(22).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3E4042"))
            }
            elevation = dpToPx(12).toFloat()
        }

        val vPad = if (isLandscape) dpToPx(6) else dpToPx(8)
        val cardHeader = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), vPad, dpToPx(12), vPad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val avSize = if (isLandscape) dpToPx(34) else dpToPx(38)
        val headerAvatarBox = FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(avSize, avSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
            setOnClickListener { onOpenApp() }
        }

        headerInitialsTxt = TextView(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        headerAvatarImg = ImageView(service).apply {
            layoutParams = FrameLayout.LayoutParams(avSize, avSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        headerAvatarBox.addView(headerInitialsTxt)
        headerAvatarBox.addView(headerAvatarImg)

        val titleCol = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(10)
            }
            setOnClickListener { onOpenApp() }
        }

        headerNameTxt = TextView(service).apply {
            textSize = if (isLandscape) 14f else 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
        }

        headerSubTxt = TextView(service).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#00A884"))
            maxLines = 1
        }

        titleCol.addView(headerNameTxt)
        titleCol.addView(headerSubTxt)

        muteBtn = createHeaderCircleButton("🔕") { onMuteToggle() }
        val clearHistoryBtn = createHeaderCircleButton("🗑️") { onClearHistory() }
        val voiceCallIcon = createMessengerVectorIcon(isVideo = false) { onVoiceCall() }
        val videoCallIcon = createMessengerVectorIcon(isVideo = true) { onVideoCall() }
        val openAppBtn = createHeaderCircleButton("↗") { onOpenApp() }

        cardHeader.addView(headerAvatarBox)
        cardHeader.addView(titleCol)
        cardHeader.addView(muteBtn)
        cardHeader.addView(clearHistoryBtn)
        cardHeader.addView(voiceCallIcon)
        cardHeader.addView(videoCallIcon)
        cardHeader.addView(openAppBtn)

        incomingCallBanner = TextView(service).apply {
            text = "📞 GELEN ARAMA VAR • Katılmak İçin Dokun"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setBackgroundColor(Color.parseColor("#00C853"))
            visibility = View.GONE
            setOnClickListener { onAnswerIncomingCall() }
        }

        messageAdapter = MessageAdapter(
            service = service,
            repository = repository,
            onPhotoClick = { bmp -> showFullScreenPhoto(bmp) },
            onAudioClick = { uri -> onPlayAudio(uri) },
            onOpenAppClick = { onOpenApp() }
        )

        messagesRecycler = RecyclerView(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            clipToPadding = false
            layoutManager = LinearLayoutManager(service).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        extraToolPanel = FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#202124"))
            visibility = View.GONE
        }

        val inputBar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), vPad, dpToPx(8), vPad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat()
                )
            }
        }

        val emojiBtn = createActionIconButton("😀") { toggleEmojiPanel() }
        val photoBtn = createActionIconButton("📷") { togglePhotoPickerPanel(onSharePhoto) }

        replyEdit = EditText(service).apply {
            val h = if (isLandscape) dpToPx(36) else dpToPx(40)
            layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply {
                leftMargin = dpToPx(4)
                rightMargin = dpToPx(4)
            }
            hint = "Mesaj yaz..."
            setHintTextColor(Color.parseColor("#8A8D91"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(20).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSendMessage()
                    true
                } else false
            }
        }

        val voiceBtn = createActionIconButton("🎤") { toggleVoicePanel(onStartVoiceDictation, onOpenApp) }

        sendCircleBtn = TextView(service).apply {
            text = "➤"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = if (isLandscape) dpToPx(36) else dpToPx(40)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
            setOnClickListener { onSendMessage() }
        }

        inputBar.addView(emojiBtn)
        inputBar.addView(photoBtn)
        inputBar.addView(replyEdit)
        inputBar.addView(voiceBtn)
        inputBar.addView(sendCircleBtn)

        chatCard.addView(cardHeader)
        chatCard.addView(incomingCallBanner)
        chatCard.addView(messagesRecycler)
        chatCard.addView(extraToolPanel)
        chatCard.addView(inputBar)

        mainColumn.addView(topBar)
        mainColumn.addView(chatCard)

        fullScreenImageOverlay = FrameLayout(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#EE000000"))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }

        overlay.addView(mainColumn)
        overlay.addView(fullScreenImageOverlay)
        rootOverlay = overlay
        return overlay
    }

    fun submitMessages(messages: List<ChatMessage>, forceScrollBottom: Boolean) {
        val rv = messagesRecycler ?: return
        val wasAtBottom = !rv.canScrollVertically(1)
        val snapshot = messages.toList()
        messageAdapter?.submitList(snapshot) {
            if (forceScrollBottom || wasAtBottom) {
                if (snapshot.isNotEmpty()) {
                    rv.scrollToPosition(snapshot.size - 1)
                }
            }
        }
    }

    fun updateInputEnabled(canReply: Boolean) {
        replyEdit?.isEnabled = canReply
        if (canReply) {
            replyEdit?.hint = "Mesaj yaz..."
            sendCircleBtn?.alpha = 1.0f
        } else {
            replyEdit?.setText("")
            replyEdit?.hint = "Yanıtlamak için sağ üstten ↗ aç"
            sendCircleBtn?.alpha = 0.4f
        }
    }

    private fun createHeaderCircleButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(service).apply {
            text = symbol
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4599FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                rightMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#263951"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createActionIconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(service).apply {
            text = symbol
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(34)).apply {
                rightMargin = dpToPx(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3B3C"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleEmojiPanel() {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "EMOJI") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "EMOJI"

        val emojis = listOf(
            "😂", "❤️", "👍", "🔥", "😍", "🙏", "😭", "😎",
            "🤔", "👏", "🎉", "💯", "🥰", "🤣", "😅", "👌",
            "💪", "🙌", "😘", "👀", "😡", "😱", "🌹", "✨"
        )

        val scroll = HorizontalScrollView(service).apply {
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }

        emojis.forEach { emo ->
            val tv = TextView(service).apply {
                text = emo
                textSize = 22f
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                setOnClickListener {
                    if (replyEdit?.isEnabled == true) {
                        replyEdit?.append(emo)
                    }
                }
            }
            row.addView(tv)
        }

        scroll.addView(row)
        panel.addView(scroll)
        panel.visibility = View.VISIBLE
    }

    private fun togglePhotoPickerPanel(onSharePhoto: (Uri) -> Unit) {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "PHOTO") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "PHOTO"

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }

        if (!repository.hasPhotoPermission(service)) {
            val info = TextView(service).apply {
                text = "Galeriden fotoğraf seçmek için SuperBubble ana ekranından 3. İzin butonuna bas kanka."
                textSize = 12f
                setTextColor(Color.LTGRAY)
            }
            container.addView(info)
            panel.addView(container)
            panel.visibility = View.VISIBLE
            return
        }

        val hint = TextView(service).apply {
            text = "📷 Göndermek istediğin fotoğrafa dokun (Önizlemeler yükleniyor...):"
            textSize = 11.5f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dpToPx(6))
        }
        container.addView(hint)

        val hScroll = HorizontalScrollView(service)
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        hScroll.addView(row)
        container.addView(hScroll)
        panel.addView(container)
        panel.visibility = View.VISIBLE

        // S22 Ultra'da bellek yememesi için arka planda küçük Thumbnail olarak yükle
        repository.ioExecutor.execute {
            val uris = repository.getRecentGalleryPhotos(service, 10)
            uris.forEach { uri ->
                val thumb = repository.loadGalleryThumbnail(service, uri)
                if (thumb != null) {
                    service.mainHandler.post {
                        val img = ImageView(service).apply {
                            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64)).apply {
                                rightMargin = dpToPx(8)
                            }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(thumb)
                            setOnClickListener { onSharePhoto(uri) }
                        }
                        row.addView(img)
                    }
                }
            }
        }
    }

    private fun toggleVoicePanel(onStartVoiceDictation: () -> Unit, onOpenApp: () -> Unit) {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "VOICE") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "VOICE"

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }

        val voiceToTextBtn = TextView(service).apply {
            text = "🎙️ Konuşarak Yazdır"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(6)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0084FF"))
                cornerRadius = dpToPx(14).toFloat()
            }
            setOnClickListener { onStartVoiceDictation() }
        }

        val directVoiceNoteBtn = TextView(service).apply {
            text = "🔊 Ses Kaydı At ↗"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00A884"))
                cornerRadius = dpToPx(14).toFloat()
            }
            setOnClickListener { onOpenApp() }
        }

        row.addView(voiceToTextBtn)
        row.addView(directVoiceNoteBtn)
        panel.addView(row)
        panel.visibility = View.VISIBLE
    }

    private fun showFullScreenPhoto(bmp: Bitmap) {
        fullScreenImageOverlay?.removeAllViews()
        val img = ImageView(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ).apply {
                val m = dpToPx(20)
                setMargins(m, m, m, m)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        val closeHint = TextView(service).apply {
            text = "✕ Kapatmak için dokun"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dpToPx(30) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA333333"))
                cornerRadius = dpToPx(20).toFloat()
            }
        }
        fullScreenImageOverlay?.addView(img)
        fullScreenImageOverlay?.addView(closeHint)
        fullScreenImageOverlay?.visibility = View.VISIBLE
    }

    private fun createMessengerVectorIcon(isVideo: Boolean, onClick: () -> Unit): ImageView {
        val sizePx = dpToPx(32)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0084FF")
            style = Paint.Style.FILL
        }

        val w = sizePx.toFloat()
        if (isVideo) {
            val bodyRect = RectF(w * 0.16f, w * 0.28f, w * 0.64f, w * 0.72f)
            canvas.drawRoundRect(bodyRect, w * 0.10f, w * 0.10f, paint)
            val lensPath = Path().apply {
                moveTo(w * 0.66f, w * 0.43f)
                lineTo(w * 0.86f, w * 0.30f)
                lineTo(w * 0.86f, w * 0.70f)
                lineTo(w * 0.66f, w * 0.57f)
                close()
            }
            canvas.drawPath(lensPath, paint)
        } else {
            val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0084FF")
                style = Paint.Style.STROKE
                strokeWidth = w * 0.12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path().apply {
                moveTo(w * 0.26f, w * 0.24f)
                lineTo(w * 0.38f, w * 0.24f)
                lineTo(w * 0.44f, w * 0.38f)
                lineTo(w * 0.35f, w * 0.46f)
                quadTo(w * 0.44f, w * 0.60f, w * 0.54f, w * 0.65f)
                lineTo(w * 0.62f, w * 0.56f)
                lineTo(w * 0.76f, w * 0.62f)
                lineTo(w * 0.76f, w * 0.74f)
                quadTo(w * 0.42f, w * 0.76f, w * 0.24f, w * 0.26f)
            }
            canvas.drawPath(path, phonePaint)
        }

        return ImageView(service).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                rightMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#263951"))
            }
            setImageBitmap(bmp)
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}