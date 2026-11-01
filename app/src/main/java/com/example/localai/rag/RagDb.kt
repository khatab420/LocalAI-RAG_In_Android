package com.example.localai.rag

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["uri"], unique = true)])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val folderUri: String?,          // set when the file came from an added folder
    val chunkCount: Int,
    val indexedAt: Long = System.currentTimeMillis()
)

@Entity(indices = [Index("docId")])
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val idx: Int,
    val text: String,
    val embedding: ByteArray? = null   // little-endian float32, L2-normalized
)

@Entity
data class FolderEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val lastSync: Long = 0
)

// ---- lightweight query results ----
data class ChunkText(val id: Long, val docId: Long, val text: String)
data class ChunkIdText(val id: Long, val text: String)
class ChunkVec(val id: Long, val embedding: ByteArray)
data class HitRow(val id: Long, val docId: Long, val idx: Int, val text: String, val docName: String)

@Dao
interface RagDao {
    @Insert suspend fun insertDoc(doc: DocumentEntity): Long
    @Insert suspend fun insertChunks(chunks: List<ChunkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFolder(folder: FolderEntity)

    @Query("SELECT * FROM DocumentEntity ORDER BY name COLLATE NOCASE") fun docs(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM DocumentEntity") suspend fun allDocs(): List<DocumentEntity>
    @Query("SELECT * FROM DocumentEntity WHERE uri = :uri LIMIT 1") suspend fun docByUri(uri: String): DocumentEntity?
    @Query("SELECT COUNT(*) FROM DocumentEntity") suspend fun docCount(): Int

    @Query("DELETE FROM ChunkEntity WHERE docId = :docId") suspend fun deleteChunks(docId: Long)
    @Query("DELETE FROM DocumentEntity WHERE id = :docId") suspend fun deleteDoc(docId: Long)

    @Query("SELECT * FROM FolderEntity ORDER BY name COLLATE NOCASE") fun folders(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM FolderEntity") suspend fun allFolders(): List<FolderEntity>
    @Query("DELETE FROM FolderEntity WHERE uri = :uri") suspend fun deleteFolder(uri: String)
    @Query("UPDATE FolderEntity SET lastSync = :t WHERE uri = :uri") suspend fun touchFolder(uri: String, t: Long)

    @Query("SELECT COUNT(*) FROM ChunkEntity") fun chunkCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM ChunkEntity WHERE embedding IS NOT NULL") fun embeddedCount(): Flow<Int>

    @Query("SELECT id, docId, text FROM ChunkEntity") suspend fun chunkTexts(): List<ChunkText>
    @Query("SELECT id, embedding FROM ChunkEntity WHERE embedding IS NOT NULL") suspend fun vectors(): List<ChunkVec>
    @Query("SELECT id, text FROM ChunkEntity WHERE embedding IS NULL OR length(embedding) != :bytes")
    suspend fun needEmbedding(bytes: Int): List<ChunkIdText>
    @Query("UPDATE ChunkEntity SET embedding = :emb WHERE id = :id") suspend fun setEmbedding(id: Long, emb: ByteArray)

    @Query(
        "SELECT c.id AS id, c.docId AS docId, c.idx AS idx, c.text AS text, d.name AS docName " +
            "FROM ChunkEntity c JOIN DocumentEntity d ON d.id = c.docId WHERE c.id IN (:ids)"
    )
    suspend fun hits(ids: List<Long>): List<HitRow>
}

@Database(
    entities = [DocumentEntity::class, ChunkEntity::class, FolderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RagDb : RoomDatabase() {
    abstract fun dao(): RagDao
}
