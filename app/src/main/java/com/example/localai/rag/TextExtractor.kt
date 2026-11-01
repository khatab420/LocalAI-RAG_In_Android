package com.example.localai.rag

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Turns a file (picked through the system file picker) into plain text. */
object TextExtractor {

    private val PLAIN = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "yaml", "yml", "toml", "ini", "cfg",
        "tex", "srt", "vtt", "rst",
        "kt", "java", "py", "js", "ts", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "sql", "sh"
    )
    private val HTML = setOf("html", "htm")

    private const val MAX_CHARS = 12_000_000

    fun supports(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PLAIN || ext in HTML || ext == "pdf" || ext == "docx"
    }

    val supportedHint = "txt, md, csv, json, html, pdf, docx and common code/text files"

    fun extract(context: Context, uri: Uri, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $name")
        val text = input.use { stream ->
            when {
                ext == "pdf" -> pdfText(stream)
                ext == "docx" -> docxText(stream)
                ext in HTML -> htmlToText(stream.bufferedReader(Charsets.UTF_8).readText())
                ext in PLAIN -> stream.bufferedReader(Charsets.UTF_8).readText()
                else -> error("Unsupported file type: .$ext")
            }
        }
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
    }

    private fun pdfText(input: InputStream): String =
        PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) error("This PDF is password protected")
            PDFTextStripper().getText(doc)
        }

    private fun docxText(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")
                    val sb = StringBuilder()
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "w:t" -> sb.append(parser.nextText())
                                "w:tab" -> sb.append('\t')
                                "w:br" -> sb.append('\n')
                            }
                        } else if (event == XmlPullParser.END_TAG && parser.name == "w:p") {
                            sb.append("\n\n")
                        }
                        event = parser.next()
                    }
                    return sb.toString()
                }
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
}
