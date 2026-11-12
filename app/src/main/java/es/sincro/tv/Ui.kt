package es.sincro.tv

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pequeñas ayudas para montar las pantallas en código.
 * Estética a juego con el launcher «Inicio»: fondo crema con manchas pastel, tarjetas blancas translúcidas,
 * foco melocotón y tipografía Outfit.
 */
object Ui {
    /** Texto principal y secundario */
    const val FG = 0xFF2E2A35.toInt()
    const val MUTED = 0xFF5E5966.toInt()
    /** Horas y detalles destacados */
    const val ACCENT = 0xFFD9506B.toInt()
    /** ★ de tus equipos */
    const val STAR = 0xFFF0A23A.toInt()
    /** Foco (melocotón), fondo crema y borde suave de las tarjetas */
    const val FOCUS = 0xFFFFC09A.toInt()
    const val BG = 0xFFF3E9E4.toInt()
    const val LINE = 0x55B9B2C7
    /** Diálogos claros, a juego */
    val DIALOG = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val fonts = HashMap<Int, Typeface>()

    /** Outfit: 300 fina, 400 normal, 500 media. */
    fun Context.font(weight: Int): Typeface = fonts.getOrPut(weight) {
        try { Typeface.createFromAsset(assets, "fonts/outfit_w$weight.ttf") } catch (e: Exception) { Typeface.DEFAULT }
    }

    fun Context.text(size: Float, color: Int = FG, bold: Boolean = false, mono: Boolean = false, light: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = font(if (light) 300 else if (bold || mono) 500 else 400)
        includeFontPadding = false
        if (mono) fontFeatureSettings = "tnum"
    }

    fun Context.button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(FG)
        typeface = font(500)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setBackgroundResource(R.drawable.item_bg)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        stateListAnimator = null
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** "★ " en naranja delante de un texto. */
    fun starred(text: String): CharSequence = SpannableStringBuilder("★  $text").apply {
        setSpan(ForegroundColorSpan(STAR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Fondo crema con manchas de color difuminadas (como «Inicio»). */
    fun Context.background(): Drawable {
        val dm = resources.displayMetrics
        val size = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        fun blob(color: Int, x: Float, y: Float, r: Float) = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, color and 0x00FFFFFF),
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(x, y)
            gradientRadius = r * size
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF7EFEA.toInt(), BG, 0xFFEEE7F1.toInt()))
        return LayerDrawable(arrayOf(
            base,
            blob(0xB3FFC9AE.toInt(), 0.40f, 0.62f, 0.30f),  // melocotón
            blob(0x99CDB8F2.toInt(), 0.66f, 0.40f, 0.28f),  // lavanda
            blob(0x80B9D9F6.toInt(), 0.92f, 0.78f, 0.30f),  // azul claro
            blob(0x80F8B9C9.toInt(), 0.08f, 0.98f, 0.25f),  // rosa
            blob(0x66FFFFFF, 0.12f, 0.08f, 0.30f),           // brillo blanco
        ))
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
