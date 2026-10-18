package com.selim.superbubble

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class ChatMessage(
    val id: String,
    val chatKey: String,
    val senderName: String? = null,
    val text: String,
    val isMe: Boolean,
    val time: String,
    val timestamp: Long,
    val imagePath: String? = null,
    val audioUri: String? = null,
    val isDelivered: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatKey: String,
    val senderName: String?,
    val text: String,
    val isMe: Boolean,
    val time: String,
    val timestamp: Long,
    val imagePath: String?,
    val audioUri: String?,
    val isDelivered: Boolean
) {
    fun toDomain() = ChatMessage(
        id = id,
        chatKey = chatKey,
        senderName = senderName,
        text = text,
        isMe = isMe,
        time = time,
        timestamp = timestamp,
        imagePath = imagePath,
        audioUri = audioUri,
        isDelivered = isDelivered
    )

    companion object {
        fun fromDomain(m: ChatMessage) = MessageEntity(
            id = m.id,
            chatKey = m.chatKey,
            senderName = m.senderName,
            text = m.text,
            isMe = m.isMe,
            time = m.time,
            timestamp = m.timestamp,
            imagePath = m.imagePath,
            audioUri = m.audioUri,
            isDelivered = m.isDelivered
        )
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatKey = :chatKey ORDER BY timestamp ASC LIMIT 120")
    fun getMessagesForChat(chatKey: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isDelivered = 1 WHERE chatKey = :chatKey AND isMe = 1 AND text = :text")
    fun markDelivered(chatKey: String, text: String)

    @Query("DELETE FROM messages WHERE chatKey = :chatKey")
    fun clearChat(chatKey: String)

    @Query(
        "DELETE FROM messages WHERE chatKey = :chatKey AND id NOT IN " +
        "(SELECT id FROM messages WHERE chatKey = :chatKey ORDER BY timestamp DESC LIMIT 120)"
    )
    fun trimChatHistory(chatKey: String)
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "superbubble_room.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

class ChatRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.messageDao()
    val ioExecutor = Executors.newFixedThreadPool(2)

    fun loadMessagesAsync(chatKey: String, onLoaded: (List<ChatMessage>) -> Unit) {
        ioExecutor.execute {
            val list = try {
                dao.getMessagesForChat(chatKey).map { it.toDomain() }
            } catch (_: Exception) {
                emptyList()
            }
            onLoaded(list)
        }
    }

    fun saveMessagesAsync(chatKey: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        ioExecutor.execute {
            try {
                dao.insertMessages(messages.map { MessageEntity.fromDomain(it) })
                dao.trimChatHistory(chatKey)
            } catch (_: Exception) {}
        }
    }

    fun markDeliveredAsync(chatKey: String, confirmedTexts: List<String>, onDone: () -> Unit) {
        ioExecutor.execute {
            try {
                for (t in confirmedTexts) {
                    dao.markDelivered(chatKey, t)
                }
            } catch (_: Exception) {}
            onDone()
        }
    }

    fun clearChatAsync(context: Context, chatKey: String) {
        ioExecutor.execute {
            try {
                val existing = dao.getMessagesForChat(chatKey)
                existing.forEach { entity ->
                    entity.imagePath?.let { path ->
                        try { File(path).delete() } catch (_: Exception) {}
                    }
                }
                dao.clearChat(chatKey)
            } catch (_: Exception) {}
        }
    }

    fun saveBitmapToFile(context: Context, bmp: Bitmap?): String? {
        if (bmp == null) return null
        return try {
            val dir = File(context.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()

            val maxDim = 720
            val ratio = min(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * ratio).toInt()),
                    max(1, (bmp.height * ratio).toInt()),
                    true
                )
            } else bmp

            val file = File(dir, "img_${System.currentTimeMillis()}_${bmp.hashCode()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun loadBitmapFromFile(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadGalleryThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(180, 180), null)
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hasPhotoPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun fetchLatestWhatsAppPhoto(context: Context, minDateAddedSec: Long): String? {
        if (!hasPhotoPermission(context)) return null
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf(
                (minDateAddedSec - 6).toString(),
                "%WhatsApp Images%"
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val bmp = loadBitmapFromUri(context, uri)
                    return saveBitmapToFile(context, bmp)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun getRecentGalleryPhotos(context: Context, limit: Int = 12): List<Uri> {
        val list = mutableListOf<Uri>()
        if (!hasPhotoPermission(context)) return list
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && list.size < limit) {
                    val id = cursor.getLong(idCol)
                    list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                }
            }
        } catch (_: Exception) {}
        return list
    }
}

object SettingsManager {
    private const val PREFS_NAME = "superbubble_settings"
    private const val KEY_ENABLED_PKGS = "enabled_packages"
    private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
    private const val KEY_FILTER_MODE = "filter_mode" // 0: Herkes, 1: Sessize Alınanları Gizle, 2: Sadece Seçili Kişiler
    private const val KEY_MUTED_SENDERS = "muted_senders"
    private const val KEY_ALLOWED_SENDERS = "allowed_senders"

    val ALL_SUPPORTED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    fun getEnabledPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ENABLED_PKGS, ALL_SUPPORTED_PACKAGES) ?: ALL_SUPPORTED_PACKAGES
    }

    fun setPackageEnabled(context: Context, pkg: String, enabled: Boolean) {
        val current = getEnabledPackages(context).toMutableSet()
        if (enabled) current.add(pkg) else current.remove(pkg)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED_PKGS, current).apply()
    }

    fun getBubbleSizeDp(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BUBBLE_SIZE_DP, 68)
    }

    fun setBubbleSizeDp(context: Context, sizeDp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BUBBLE_SIZE_DP, sizeDp).apply()
    }

    fun getFilterMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FILTER_MODE, 0)
    }

    fun setFilterMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FILTER_MODE, mode).apply()
    }

    fun getMutedSenders(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_MUTED_SENDERS, emptySet()) ?: emptySet()
    }

    fun toggleMuteSender(context: Context, sender: String): Boolean {
        val norm = sender.trim().lowercase(Locale.getDefault())
        val current = getMutedSenders(context).toMutableSet()
        val nowMuted = if (current.contains(norm)) {
            current.remove(norm)
            false
        } else {
            current.add(norm)
            true
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MUTED_SENDERS, current).apply()
        return nowMuted
    }

    fun isSenderMuted(context: Context, sender: String): Boolean {
        val norm = sender.trim().lowercase(Locale.getDefault())
        return getMutedSenders(context).contains(norm)
    }

    fun getAllowedSendersCsv(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALLOWED_SENDERS, "") ?: ""
    }

    fun setAllowedSendersCsv(context: Context, csv: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALLOWED_SENDERS, csv).apply()
    }

    fun shouldShowNotification(context: Context, pkg: String, sender: String): Boolean {
        if (!getEnabledPackages(context).contains(pkg)) return false
        val normSender = sender.trim().lowercase(Locale.getDefault())
        if (isSenderMuted(context, normSender)) return false

        val mode = getFilterMode(context)
        if (mode == 2) {
            val allowed = getAllowedSendersCsv(context)
                .split(",")
                .map { it.trim().lowercase(Locale.getDefault()) }
                .filter { it.isNotEmpty() }
            if (allowed.isNotEmpty() && allowed.none { normSender.contains(it) }) {
                return false
            }
        }
        return true
    }
}