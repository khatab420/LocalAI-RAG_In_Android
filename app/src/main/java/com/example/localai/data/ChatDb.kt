package com.example.localai.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String
)

@Dao
interface ChatDao {
    @Insert suspend fun insertChat(chat: ChatEntity): Long
    @Insert suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM ChatEntity ORDER BY createdAt DESC")
    fun chats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM MessageEntity WHERE chatId = :chatId ORDER BY id")
    suspend fun messages(chatId: Long): List<MessageEntity>

    @Query("DELETE FROM MessageEntity WHERE chatId = :chatId")
    suspend fun deleteMessages(chatId: Long)

    @Query("DELETE FROM ChatEntity WHERE id = :chatId")
    suspend fun deleteChat(chatId: Long)
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDb : RoomDatabase() {
    abstract fun dao(): ChatDao
}
