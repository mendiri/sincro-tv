package es.sincro.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Actualizaciones desde GitHub (github.com/mendiri/sincro-tv): mira la última versión publicada,
 * la descarga y abre el instalador de Android.
 */
object Updater {
    const val REPO = "mendiri/sincro-tv"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val versionName: String, val versionCode: Int, val apkUrl: String, val notes: String)

    fun installedCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** Última versión publicada. Bloquea: llamar fuera del hilo principal. */
    fun latest(): Release {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "SincroTV").build()
        val body = Http.client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("GitHub respondió con error ${it.code}")
            it.body!!.string()
        }
        val o = JSONObject(body)
        val notes = o.optString("body")
        // El número de versión va en la descripción de la publicación: "versionCode: 17"
        val code = Regex("versionCode:\\s*(\\d+)").find(notes)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("La publicación no indica su versionCode")
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: throw IOException("La publicación no tiene APK")
        return Release(o.optString("tag_name").removePrefix("v"), code, apk.getString("browser_download_url"),
            notes.lines().filterNot { it.startsWith("versionCode:") }.joinToString("\n").trim())
    }

    /** Descarga el APK. [progress] recibe 0..100. Bloquea. */
    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "actualizaciones").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "SincroTV-${r.versionCode}.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "SincroTV").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("La descarga respondió con error ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength()
            body.byteStream().use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) { val pc = (done * 100 / total).toInt(); if (pc != last) { last = pc; progress(pc) } }
                    }
                }
            }
        }
        if (f.length() < 100_000) throw IOException("El archivo descargado no parece un APK")
        if (Build.VERSION.SDK_INT < 24) f.setReadable(true, false)
        return f
    }

    /** ¿Puede esta app pedir instalar APKs? (Android 8+ pide permiso una vez) */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Abre los ajustes para permitir instalar apps desde Sincro TV. Devuelve false si el Fire TV no tiene esa pantalla. */
    fun openInstallPermission(a: Activity): Boolean = try {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        true
    } catch (e: Exception) { false }

    /** Abre el instalador de Android con el APK descargado. */
    fun install(a: Activity, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, "${a.packageName}.actualizaciones", apk) else Uri.fromFile(apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
