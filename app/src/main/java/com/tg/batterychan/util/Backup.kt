package com.tg.batterychan.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One zip file with every setting and every picked picture / video / sound.
 * File paths are stored relative to the app's files folder so a backup restores anywhere.
 */
object Backup {
    private const val PREFS_NAME = "oni_chan_prefs"
    private const val SETTINGS_ENTRY = "settings.json"
    private const val FILES_DIR = "files/"
    private const val TOKEN = "@files/"

    fun export(context: Context, out: OutputStream) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = context.filesDir.absolutePath + "/"
        val files = linkedSetOf<String>()
        val entries = JSONArray()

        for ((key, value) in prefs.all) {
            val o = JSONObject().put("k", key)
            when (value) {
                is Boolean -> o.put("t", "b").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value.toDouble())
                is String -> o.put("t", "s").put("v", mapOut(value, base, files))
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(value.map { mapOut(it.toString(), base, files) }))
                else -> continue
            }
            entries.put(o)
        }

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(JSONObject().put("version", 1).put("prefs", entries).toString().toByteArray())
            zip.closeEntry()
            for (relative in files) {
                val file = File(context.filesDir, relative)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(FILES_DIR + relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces all settings and media with the ones in the backup. Returns false if it is not a backup. */
    fun import(context: Context, input: InputStream): Boolean {
        var json: String? = null
        val root = context.filesDir.canonicalFile

        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == SETTINGS_ENTRY) {
                    json = zip.readBytes().toString(Charsets.UTF_8)
                } else if (name.startsWith(FILES_DIR) && !entry.isDirectory) {
                    val dest = File(root, name.removePrefix(FILES_DIR)).canonicalFile
                    // Never write outside the app's files folder.
                    if (dest.path.startsWith(root.path + File.separator)) {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val text = json ?: return false
        val entries = try {
            JSONObject(text).getJSONArray("prefs")
        } catch (e: Exception) {
            return false
        }
        val base = context.filesDir.absolutePath + "/"
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            val key = o.getString("k")
            when (o.getString("t")) {
                "b" -> editor.putBoolean(key, o.getBoolean("v"))
                "i" -> editor.putInt(key, o.getInt("v"))
                "l" -> editor.putLong(key, o.getLong("v"))
                "f" -> editor.putFloat(key, o.getDouble("v").toFloat())
                "s" -> editor.putString(key, mapIn(o.getString("v"), base))
                "ss" -> {
                    val array = o.getJSONArray("v")
                    editor.putStringSet(key, (0 until array.length()).map { mapIn(array.getString(it), base) }.toSet())
                }
            }
        }
        editor.commit()
        return true
    }

    private fun mapOut(value: String, base: String, files: MutableSet<String>): String {
        if (!value.startsWith(base)) return value
        val relative = value.removePrefix(base)
        files.add(relative)
        return TOKEN + relative
    }

    private fun mapIn(value: String, base: String): String =
        if (value.startsWith(TOKEN)) base + value.removePrefix(TOKEN) else value
}
