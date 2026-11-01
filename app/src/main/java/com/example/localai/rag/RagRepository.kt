package com.example.localai.rag

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.localai.llm.EmbeddingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Knowledge base: indexes documents into passages (with optional embeddings) and retrieves
 * the passages most relevant to a question (hybrid: vector search + BM25 keyword search).
 */
class RagRepository(
    private val app: Context,
    private val db: RagDb,
    private val embedder: EmbeddingEngine
) {
    private val dao = db.dao()
    private val writeLock = Mutex()   // one indexing job at a time
    private val indexLock = Mutex()   // guards the in-memory search indexes

    @Volatile private var bm25: Bm25Index? = null
    @Volatile private var vecIndex: VecIndex? = null

    val documents: Flow<List<DocumentEntity>> = dao.docs()
    val folders: Flow<List<FolderEntity>> = dao.folders()
    val chunkCount: Flow<Int> = dao.chunkCount()
    val embeddedCount: Flow<Int> = dao.embeddedCount()

    suspend fun docCount(): Int = dao.docCount()

    private fun invalidate() { bm25 = null; vecIndex = null }

    // ------------------------------------------------------------------------------------
    // adding / syncing / removing
    // ------------------------------------------------------------------------------------

    /** Index individual files picked by the user. Returns a list of per-file problems (empty = all fine). */
    suspend fun addFiles(uris: List<Uri>, report: (String) -> Unit): List<String> = writeLock.withLock {
        val problems = ArrayList<String>()
        for ((i, uri) in uris.withIndex()) {
            val file = DocumentFile.fromSingleUri(app, uri)
            val name = file?.name ?: uri.lastPathSegment ?: "file"
            if (!TextExtractor.supports(name)) {
                problems += "$name: unsupported type (supported: ${TextExtractor.supportedHint})"
                continue
            }
            try {
                indexOne(uri, name, file?.length() ?: 0, file?.lastModified() ?: 0, null) { report("[${i + 1}/${uris.size}] $it") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problems += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        problems
    }

    /** Remember a folder and index every supported file in it (recursively). */
    suspend fun addFolder(treeUri: Uri, report: (String) -> Unit): List<String> = writeLock.withLock {
        val root = DocumentFile.fromTreeUri(app, treeUri) ?: return@withLock listOf("Cannot open that folder")
        dao.upsertFolder(FolderEntity(treeUri.toString(), root.name ?: "Folder"))
        syncFolderLocked(treeUri.toString(), report)
    }

    /** Re-scan all remembered folders: index new/changed files, drop deleted ones. */
    suspend fun syncFolders(report: (String) -> Unit): List<String> = writeLock.withLock {
        val problems = ArrayList<String>()
        for (f in dao.allFolders()) problems += syncFolderLocked(f.uri, report)
        problems
    }

    private suspend fun syncFolderLocked(folderUri: String, report: (String) -> Unit): List<String> {
        val problems = ArrayList<String>()
        val root = DocumentFile.fromTreeUri(app, Uri.parse(folderUri))
        if (root == null || !root.exists()) return listOf("Folder is no longer accessible: $folderUri")

        report("Scanning ${root.name ?: "folder"}…")
        val files = ArrayList<DocumentFile>()
        withContext(Dispatchers.IO) { walk(root, 0, files) }

        val existing = dao.allDocs().filter { it.folderUri == folderUri }.associateBy { it.uri }
        val seen = HashSet<String>()
        for ((i, f) in files.withIndex()) {
            val uri = f.uri.toString()
            seen += uri
            val old = existing[uri]
            if (old != null && old.sizeBytes == f.length() && old.lastModified == f.lastModified()) continue
            val name = f.name ?: "file"
            try {
                indexOne(f.uri, name, f.length(), f.lastModified(), folderUri) { report("[${i + 1}/${files.size}] $it") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problems += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        for ((uri, doc) in existing) if (uri !in seen) removeDoc(doc.id)
        dao.touchFolder(folderUri, System.currentTimeMillis())
        invalidate()
        return problems
    }

    private fun walk(dir: DocumentFile, depth: Int, out: MutableList<DocumentFile>) {
        if (depth > 6) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, depth + 1, out)
            else if (f.isFile && TextExtractor.supports(f.name ?: "")) out += f
        }
    }

    suspend fun deleteDocument(id: Long) = writeLock.withLock { removeDoc(id); invalidate() }

    suspend fun deleteFolder(folder: FolderEntity) = writeLock.withLock {
        dao.allDocs().filter { it.folderUri == folder.uri }.forEach { removeDoc(it.id) }
        dao.deleteFolder(folder.uri)
        invalidate()
    }

    private suspend fun removeDoc(id: Long) = db.withTransaction {
        dao.deleteChunks(id)
        dao.deleteDoc(id)
    }

    // ------------------------------------------------------------------------------------
    // indexing one document
    // ------------------------------------------------------------------------------------

    private suspend fun indexOne(
        uri: Uri, name: String, size: Long, lastModified: Long, folderUri: String?,
        report: (String) -> Unit
    ) {
        report("Reading $name…")
        val text = withContext(Dispatchers.IO) { TextExtractor.extract(app, uri, name) }
        val pieces = Chunker.chunk(text).take(MAX_CHUNKS_PER_DOC)
        if (pieces.isEmpty()) error("no readable text found (scanned PDF or empty file?)")

        val embeddings = arrayOfNulls<ByteArray>(pieces.size)
        if (embedder.isLoaded) {
            val prefix = EmbedProfile.doc(embedder.loadedName)
            for (i in pieces.indices) {
                report("Embedding $name ${i + 1}/${pieces.size}")
                embeddings[i] = embedder.embed(prefix + pieces[i])?.l2Normalized()?.toBytes()
            }
        }

        db.withTransaction {
            dao.docByUri(uri.toString())?.let { dao.deleteChunks(it.id); dao.deleteDoc(it.id) }
            val docId = dao.insertDoc(
                DocumentEntity(
                    uri = uri.toString(), name = name, sizeBytes = size,
                    lastModified = lastModified, folderUri = folderUri, chunkCount = pieces.size
                )
            )
            dao.insertChunks(pieces.mapIndexed { i, p -> ChunkEntity(docId = docId, idx = i, text = p, embedding = embeddings[i]) })
        }
        invalidate()
    }

    /** Compute embeddings for passages that don't have one yet (e.g. after loading an embedding model). */
    suspend fun embedMissing(report: (String) -> Unit): List<String> = writeLock.withLock {
        if (!embedder.isLoaded) return@withLock listOf("Load an embedding model first (Models -> Use for RAG).")
        val todo = dao.needEmbedding(embedder.dim * 4)
        val prefix = EmbedProfile.doc(embedder.loadedName)
        for ((i, c) in todo.withIndex()) {
            report("Embedding passage ${i + 1}/${todo.size}")
            embedder.embed(prefix + c.text)?.let { dao.setEmbedding(c.id, it.l2Normalized().toBytes()) }
        }
        invalidate()
        emptyList()
    }

    // ------------------------------------------------------------------------------------
    // retrieval
    // ------------------------------------------------------------------------------------

    suspend fun search(query: String, k: Int = 4): List<Hit> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()

        val keyword = bm25Index().search(query, 20)
        val semantic = if (embedder.isLoaded) vectorSearch(query, 20) else emptyList()

        val ids = if (semantic.isEmpty()) keyword.take(k).map { it.first }
        else fuse(semantic, keyword).take(k)
        if (ids.isEmpty()) return@withContext emptyList()

        val rows = dao.hits(ids).associateBy { it.id }
        ids.mapNotNull { rows[it] }.map { Hit(it.docName, it.text, it.idx) }
    }

    private suspend fun bm25Index(): Bm25Index = indexLock.withLock {
        bm25 ?: Bm25Index(dao.chunkTexts()).also { bm25 = it }
    }

    private suspend fun vectorSearch(query: String, k: Int): List<Pair<Long, Double>> {
        val q = embedder.embed(EmbedProfile.query(embedder.loadedName) + query)?.l2Normalized() ?: return emptyList()
        val index = indexLock.withLock {
            vecIndex?.takeIf { it.dim == q.size } ?: VecIndex.build(dao.vectors(), q.size).also { vecIndex = it }
        }
        return index.search(q, k)
    }

    companion object {
        private const val MAX_CHUNKS_PER_DOC = 6000
    }
}
