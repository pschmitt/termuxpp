package dev.pschmitt.termux.styling

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.AtomicFile
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

const val DEFAULT_FILENAME = "Default"

fun capitalize(str: String): String {
    var lastWhitespace = true
    val chars = str.toCharArray()
    for (i in chars.indices) {
        if (Character.isLetter(chars[i])) {
            if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i])
            lastWhitespace = false
        } else {
            lastWhitespace = Character.isWhitespace(chars[i])
        }
    }
    return String(chars)
}

private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

private fun textColorPrimary(context: Context): Int {
    val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    return context.resources.getColor(if (dark) android.R.color.primary_text_dark else android.R.color.primary_text_light)
}

class TermuxStyleActivity : Activity() {

    internal class Selectable(val fileName: String, val isColors: Boolean) {
        val displayName: String

        init {
            var name = fileName.replace('-', ' ')
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex != -1) name = name.substring(0, dotIndex)

            this.displayName = capitalize(name)
        }

        override fun toString(): String = displayName

        fun preview(context: Context): View {
            val sample = TextView(context).apply {
                text = context.getString(R.string.preview_label)
                textSize = 14f
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8).toFloat()
                    setColor(if (isColors) readColors(context).first else Color.rgb(32, 36, 40))
                }
                setTextColor(if (isColors) readColors(context).second else Color.WHITE)
                typeface = if (isColors) Typeface.MONOSPACE else loadTypeface(context)
                contentDescription = "$displayName. ${context.getString(R.string.preview_label)}"
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
                addView(TextView(context).apply {
                    text = displayName
                    textSize = 16f
                    setTextColor(textColorPrimary(context))
                })
                addView(sample, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 6) })
            }
        }

        private fun loadTypeface(context: Context): Typeface = try {
            if (fileName == DEFAULT_FILENAME) Typeface.MONOSPACE else Typeface.createFromAsset(context.assets, "fonts/$fileName")
        } catch (_: Exception) { Typeface.MONOSPACE }

        private fun readColors(context: Context): Pair<Int, Int> {
            if (!isColors || fileName == DEFAULT_FILENAME) return Color.rgb(32, 36, 40) to Color.WHITE
            return try {
                val values = Properties().apply { context.assets.open("colors/$fileName").use { load(it) } }
                fun parse(key: String, fallback: Int): Int = try { Color.parseColor(values.getProperty(key)) } catch (_: Exception) { fallback }
                parse("background", Color.rgb(32, 36, 40)) to parse("foreground", Color.WHITE)
            } catch (_: Exception) { Color.rgb(32, 36, 40) to Color.WHITE }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Avoid dim behind:
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setContentView(R.layout.layout)

        val colorSpinner = findViewById<Button>(R.id.color_spinner)
        val fontSpinner = findViewById<Button>(R.id.font_spinner)

        val colorAdapter = previewAdapter()
        val fontAdapter = previewAdapter()

        colorSpinner.setOnClickListener { showChoices(colorAdapter, true) }
        fontSpinner.setOnClickListener { showChoices(fontAdapter, false) }

        val colorList = ArrayList<Selectable>()
        val fontList = ArrayList<Selectable>()
        for (assetType in arrayOf("colors", "fonts")) {
            val isColors = assetType == "colors"
            val assetsFileExtension = if (isColors) ".properties" else ".ttf"
            val currentList = if (isColors) colorList else fontList

            currentList.add(Selectable(DEFAULT_FILENAME, isColors))

            try {
                assets.list(assetType)!!
                        .filter { it.endsWith(assetsFileExtension) }
                        .forEach { currentList.add(Selectable(it, isColors)) }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

        }

        colorAdapter.addAll(colorList)
        fontAdapter.addAll(fontList)
    }

    private fun previewAdapter(): ArrayAdapter<Selectable> = object : ArrayAdapter<Selectable>(this, 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = getItem(position)!!.preview(this@TermuxStyleActivity)
    }

    private fun showChoices(adapter: ArrayAdapter<Selectable>, colors: Boolean) {
        val search = EditText(this).apply {
            hint = getString(if (colors) R.string.search_colors else R.string.search_fonts)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            isTextFilterEnabled = true
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ ->
                copyFile(adapter.getItem(position)!!, colors)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                showLicense(adapter.getItem(position)!!, colors)
                true
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, dp(420)))
        }
        AlertDialog.Builder(this)
            .setTitle(if (colors) R.string.color_prompt else R.string.font_prompt)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showLicense(mCurrentSelectable: Selectable, colors: Boolean) {
        try {
            val assetsFolder = if (colors) "colors" else "fonts"
            var fileName = mCurrentSelectable.fileName
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) fileName = fileName.substring(0, dotIndex)
            fileName += ".txt"

            assets.open("$assetsFolder/$fileName").use { `in` ->
                val buffer = ByteArray(`in`.available())
                `in`.read(buffer)
                val license = SpannableString(String(buffer))
                Linkify.addLinks(license, Linkify.ALL)
                val dialog = AlertDialog.Builder(this)
                        .setTitle(mCurrentSelectable.displayName)
                        .setMessage(license)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                (dialog.findViewById<View>(android.R.id.message) as TextView).movementMethod = LinkMovementMethod.getInstance()
            }
        } catch (e: IOException) {
            // Ignore.
        }

    }

    private fun copyFile(mCurrentSelectable: Selectable?, colors: Boolean) {
        val outputFile = if (colors) "colors.properties" else "font.ttf"
        try {
            val assetsFolder = if (colors) "colors" else "fonts"

            val context = createPackageContext("dev.pschmitt.termux", Context.CONTEXT_IGNORE_SECURITY)
            val homeDir = File(context.filesDir, "home")
            val termuxDir = File(homeDir, ".config/termuxpp")
            val legacyConfigDir = File(homeDir, ".config/termux")
            val legacyTermuxDir = File(homeDir, ".termux")
            val destinationDir = when {
                termuxDir.exists() -> termuxDir
                legacyConfigDir.exists() -> legacyConfigDir
                legacyTermuxDir.exists() -> legacyTermuxDir
                else -> termuxDir
            }
            if (!(destinationDir.isDirectory || destinationDir.mkdirs()))
                throw RuntimeException("Cannot create termux dir=" + destinationDir.absolutePath)

            // Use canonicalFile to follow a possible symlink:
            val destinationFile = File(destinationDir, outputFile).canonicalFile
            // Fix for if the user has messed up with chmod:
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val defaultChoice = mCurrentSelectable!!.fileName == DEFAULT_FILENAME
            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            if (defaultChoice) {
                if (colors) {
                    val comment = "# Using default color theme.".toByteArray(StandardCharsets.UTF_8)
                    out.write(comment)
                } else {
                    // Just leave an empty font file as a marker.
                }
            } else {
                assets.open(assetsFolder + "/" + mCurrentSelectable.fileName).use {
                    it.copyTo(out)
                }
            }
            atomicFile.finishWrite(out)

            // Note: Must match constant in Term#onCreate():
            val actionReload = "dev.pschmitt.termux.app.reload_style"
            val executeIntent = Intent(actionReload)
            executeIntent.putExtra(actionReload, if (colors) "colors" else "font")
            sendBroadcast(executeIntent)
        } catch (e: Exception) {
            Log.w("termux", "Failed to write $outputFile", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

    }

}
