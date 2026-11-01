package com.example.localai.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalModelStore(context: Context) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

    fun fileFor(name: String): File = File(dir, File(name).name)

    /** Copies a model picked with the system file picker into app storage. */
    suspend fun import(cr: ContentResolver, uri: Uri, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            var name = "imported-model.gguf"
            var size = -1L
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0) size = c.getLong(s)
                }
            }
            require(name.endsWith(".gguf", ignoreCase = true)) { "Please pick a .gguf file" }

            val out = fileFor(name)
            val tmp = File(dir, "$name.import")
            try {
                cr.openInputStream(uri)!!.use { input ->
                    val magic = ByteArray(4)
                    require(input.read(magic) == 4 && String(magic, Charsets.ISO_8859_1) == "GGUF") {
                        "This is not a valid GGUF model file"
                    }
                    tmp.outputStream().use { o ->
                        o.write(magic)
                        val buf = ByteArray(1 shl 20)
                        var done = 4L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val pct = (done * 100 / size).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / size) }
                            }
                        }
                    }
                }
                if (out.exists()) out.delete()
                check(tmp.renameTo(out)) { "Could not save the model" }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            out
        }
}
