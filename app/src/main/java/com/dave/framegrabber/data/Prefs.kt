package com.dave.framegrabber.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.dave.framegrabber.core.*
import com.dave.framegrabber.export.*
import com.dave.framegrabber.batch.*
import com.dave.framegrabber.ocr.*
import com.dave.framegrabber.ui.*
import com.dave.framegrabber.ui.widget.*
import com.dave.framegrabber.R

/** All remembered settings in one place (SharedPreferences "settings"). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    // capture output
    var format: String get() = str("fmt", "JPG"); set(v) = put { putString("fmt", v) }
    var size: String get() = str("size", "orig"); set(v) = put { putString("size", v) }
    var customW: String get() = str("cw", "1920"); set(v) = put { putString("cw", v) }
    var customH: String get() = str("ch", "1080"); set(v) = put { putString("ch", v) }
    var quality: Int get() = sp.getInt("q", 92); set(v) = put { putInt("q", v) }
    var sharpest: Boolean get() = sp.getBoolean("sharp", true); set(v) = put { putBoolean("sharp", v) }

    // batch
    var batchCount: Boolean get() = sp.getBoolean("bCount", false); set(v) = put { putBoolean("bCount", v) }
    var batchInterval: String get() = str("bInt", "5"); set(v) = put { putString("bInt", v) }
    var batchN: String get() = str("bN", "10"); set(v) = put { putString("bN", v) }
    var skipBlurry: Boolean get() = sp.getBoolean("skipBlur", false); set(v) = put { putBoolean("skipBlur", v) }
    var skipDupes: Boolean get() = sp.getBoolean("skipDup", false); set(v) = put { putBoolean("skipDup", v) }

    // export
    var gifFps: Int get() = sp.getInt("gifFps", 10); set(v) = put { putInt("gifFps", v) }
    var gifWidth: Int get() = sp.getInt("gifW", 480); set(v) = put { putInt("gifW", v) }

    // ui
    var tab: Int get() = sp.getInt("tab", 0); set(v) = put { putInt("tab", v) }
    var lastDir: String? get() = sp.getString("lastDir", null); set(v) = put { putString("lastDir", v) }

    // general
    var namePattern: String get() = str("pattern", DEFAULT_PATTERN); set(v) = put { putString("pattern", v) }
    /** SAF tree Uri of a folder picked by the user, or null = Pictures/FrameGrabber. */
    var folderTree: String? get() = sp.getString("tree", null); set(v) = put { putString("tree", v) }
    var folderTreeName: String? get() = sp.getString("treeName", null); set(v) = put { putString("treeName", v) }
    var ocrLangs: String get() = str("ocr", "eng+rus"); set(v) = put { putString("ocr", v) }

    // GIFs and clips made so far (JSON array, newest first)
    var madeJson: String get() = str("made", "[]"); set(v) = put { putString("made", v) }

    // recent videos (JSON array, newest first)
    var recentJson: String get() = str("recent", "[]"); set(v) = put { putString("recent", v) }

    companion object {
        const val DEFAULT_PATTERN = "{video}_{time}"
        const val ALBUM = "FrameGrabber"
    }
}
