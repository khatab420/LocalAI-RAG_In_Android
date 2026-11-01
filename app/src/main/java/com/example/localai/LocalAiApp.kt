package com.example.localai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.localai.data.CatalogRepository
import com.example.localai.data.ChatDb
import com.example.localai.data.LocalModelStore
import com.example.localai.llm.EmbeddingEngine
import com.example.localai.llm.LlamaEngine
import com.example.localai.rag.RagDb
import com.example.localai.rag.RagRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional: URL of your own models.json (same format as app/src/main/assets/models.json).
 * When set, the app lists models from the cloud whenever it is online (and caches the list).
 * Leave empty to use the built-in list.
 */
const val REMOTE_CATALOG_URL = ""

class AppContainer(private val app: Application) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val store = LocalModelStore(app)
    val engine = LlamaEngine()
    val db: ChatDb = Room.databaseBuilder(app, ChatDb::class.java, "chat.db").build()
    val workManager: WorkManager get() = WorkManager.getInstance(app)

    val catalog = CatalogRepository(
        http = http,
        cacheFile = File(app.filesDir, "catalog_cache.json"),
        remoteUrl = REMOTE_CATALOG_URL,
        bundledJson = { app.assets.open("models.json").bufferedReader().use { it.readText() } }
    )

    // ---- RAG ----
    val embedder = EmbeddingEngine()
    private val ragDb: RagDb = Room.databaseBuilder(app, RagDb::class.java, "rag.db").build()
    val rag = RagRepository(app, ragDb, embedder)
    val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // re-load the embedding model the user picked last time
        prefs.getString("embedder", null)?.let { name ->
            val f = store.fileFor(name)
            if (f.exists()) appScope.launch { embedder.load(f.absolutePath, name) }
        }
    }

    val totalRamBytes: Long by lazy {
        val mi = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        mi.totalMem
    }
}

class LocalAiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
    }
}
