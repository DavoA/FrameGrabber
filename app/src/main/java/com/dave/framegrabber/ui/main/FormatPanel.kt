package com.dave.framegrabber.ui.main

import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.dave.framegrabber.R
import com.dave.framegrabber.core.Fmt
import com.dave.framegrabber.core.OutputSpec
import com.dave.framegrabber.ui.anim
import com.dave.framegrabber.ui.onTextChanged
import com.dave.framegrabber.ui.showAnimated
import com.dave.framegrabber.ui.toast

/** Tab «Формат»: output size, format, quality and "sharpest frame nearby". All remembered. */
class FormatPanel(private val s: MainScreen) {

    private val radioSize = s.h<RadioGroup>(R.id.radioSize)
    private val customRow = s.h<View>(R.id.customSizeRow)
    private val editW = s.h<EditText>(R.id.editW)
    private val editH = s.h<EditText>(R.id.editH)
    private val radioFormat = s.h<RadioGroup>(R.id.radioFormat)
    private val txtQuality = s.h<TextView>(R.id.txtQuality)
    private val seekQuality = s.h<SeekBar>(R.id.seekQuality)
    private val swSharpest = s.h<Switch>(R.id.swSharpest)

    /** "Sharpest frame nearby" is on. */
    val sharpest: Boolean get() = swSharpest.isChecked

    init {
        restore()   // first put the saved values in, then start listening (so restoring doesn't re-save)
        listen()
    }

    private fun restore() {
        val p = s.prefs
        radioFormat.check(when (p.format) { "PNG" -> R.id.fmtPng; "WEBP" -> R.id.fmtWebp; else -> R.id.fmtJpg })
        radioSize.check(when (p.size) { "720" -> R.id.size720; "1080" -> R.id.size1080; "custom" -> R.id.sizeCustom; else -> R.id.sizeOrig })
        customRow.visibility = if (p.size == "custom") View.VISIBLE else View.GONE
        editW.setText(p.customW); editH.setText(p.customH)
        seekQuality.progress = p.quality
        showQuality(lossless = p.format == "PNG", animate = false)
        swSharpest.isChecked = p.sharpest
    }

    private fun listen() {
        val p = s.prefs
        radioSize.setOnCheckedChangeListener { _, id ->
            if (id == R.id.sizeCustom) customRow.showAnimated() else customRow.visibility = View.GONE
            p.size = when (id) { R.id.size720 -> "720"; R.id.size1080 -> "1080"; R.id.sizeCustom -> "custom"; else -> "orig" }
        }
        radioFormat.setOnCheckedChangeListener { _, id ->
            p.format = when (id) { R.id.fmtPng -> "PNG"; R.id.fmtWebp -> "WEBP"; else -> "JPG" }
            showQuality(lossless = id == R.id.fmtPng, animate = true)
        }
        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (v < 1) sb.progress = 1
                txtQuality.text = "Качество: ${sb.progress}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { p.quality = sb.progress }
        })
        editW.onTextChanged { p.customW = it }
        editH.onTextChanged { p.customH = it }
        swSharpest.setOnCheckedChangeListener { _, on -> p.sharpest = on }
    }

    /** PNG is lossless: the quality slider doesn't apply. */
    private fun showQuality(lossless: Boolean, animate: Boolean) {
        seekQuality.isEnabled = !lossless
        val a = if (lossless) 0.35f else 1f
        if (animate) seekQuality.anim().alpha(a).setDuration(200).start() else seekQuality.alpha = a
        txtQuality.text = if (lossless) "Качество: без потерь" else "Качество: ${seekQuality.progress}"
    }

    /** What to save frames as, or a message + null if the custom size is wrong. */
    fun readSpec(): OutputSpec? {
        val fmt = when (radioFormat.checkedRadioButtonId) {
            R.id.fmtPng -> Fmt.PNG
            R.id.fmtWebp -> Fmt.WEBP
            else -> Fmt.JPG
        }
        val size = when (radioSize.checkedRadioButtonId) {
            R.id.size720 -> 1280 to 720
            R.id.size1080 -> 1920 to 1080
            R.id.sizeCustom -> {
                val w = editW.text.toString().toIntOrNull()
                val h = editH.text.toString().toIntOrNull()
                if (w == null || h == null || w !in 16..8192 || h !in 16..8192) {
                    s.act.toast("Свой размер: от 16 до 8192 пикселей"); return null
                }
                w to h
            }
            else -> null
        }
        return OutputSpec(size, fmt, seekQuality.progress.coerceAtLeast(1))
    }
}
