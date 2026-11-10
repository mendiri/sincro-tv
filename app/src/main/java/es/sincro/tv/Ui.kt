package es.sincro.tv

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pequeñas ayudas para montar las pantallas en código. */
object Ui {
    const val FG = 0xFFE8EFE9.toInt()
    const val MUTED = 0xFF93A39A.toInt()
    const val ACCENT = 0xFF3FBF74.toInt()
    const val STAR = 0xFFE5A53A.toInt()
    const val BG = 0xFF0F1A14.toInt()

    fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun Context.text(size: Float, color: Int = FG, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    fun Context.button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setBackgroundResource(R.drawable.item_bg)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
        val p = (layoutParams as? ViewGroup.MarginLayoutParams)
            ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(l, t, r, b); layoutParams = p
        return this
    }
}
