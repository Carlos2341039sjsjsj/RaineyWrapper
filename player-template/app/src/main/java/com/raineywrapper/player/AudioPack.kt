package com.raineywrapper.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Paquete de audio.
 * - Sonido de inicio: un archivo que suena al abrir el juego (cualquier consola o juego de PC).
 * - Reemplazos: para juegos de PC / web, un sonido del juego se cambia por otro archivo. Se sirve en lugar del original
 *   (se busca por la ruta sin extension, asi un .mp3 puede reemplazar a un .ogg).
 */
object AudioPack {
    private const val MAX_BYTES = 40L shl 20

    private fun dir(ctx: Context) = File(ctx.filesDir, "audiopack").also { it.mkdirs() }
    private fun startFile(ctx: Context) = File(ctx.filesDir, "startup_sound.bin")

    private fun key(rel: String): String =
        rel.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    private fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "audio.ogg"

    private fun copy(ctx: Context, uri: Uri, out: File): Boolean = try {
        val tmp = File(out.parentFile, out.name + ".tmp")
        var total = 0L
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) throw java.io.IOException("muy grande")
                    o.write(buf, 0, n)
                }
            }
        }
        out.delete()
        tmp.renameTo(out)
    } catch (e: Throwable) {
        false
    }

    fun hasStart(ctx: Context) = startFile(ctx).isFile

    fun importStart(ctx: Context, uri: Uri): Boolean = copy(ctx, uri, startFile(ctx))

    fun clearStart(ctx: Context) {
        startFile(ctx).delete()
    }

    fun playStart(ctx: Context) {
        val f = startFile(ctx)
        if (!f.isFile) return
        try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepareAsync()
            }
        } catch (e: Throwable) {
        }
    }

    /** Archivo que reemplaza al sonido pedido ("audio/bgm/Battle1.ogg"), o null. */
    fun find(ctx: Context, rel: String): File? {
        val k = key(rel)
        return dir(ctx).listFiles()?.firstOrNull { it.isFile && it.name.substringBeforeLast('.') == k }
    }

    fun import(ctx: Context, rel: String, uri: Uri): Boolean {
        val ext = displayName(ctx, uri).substringAfterLast('.', "ogg").lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "ogg" }
        dir(ctx).listFiles()?.filter { it.name.substringBeforeLast('.') == key(rel) }?.forEach { it.delete() }
        return copy(ctx, uri, File(dir(ctx), key(rel) + "." + ext))
    }

    fun count(ctx: Context): Int = dir(ctx).listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0

    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
