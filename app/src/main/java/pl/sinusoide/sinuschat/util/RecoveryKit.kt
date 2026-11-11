package pl.sinusoide.sinuschat.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.setup.SetupActivity
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date

object RecoveryKit {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 48f

    private const val INK = 0xFF10212A.toInt()
    private const val MUTED = 0xFF5B7280.toInt()
    private const val ACCENT = 0xFF0A93A8.toInt()
    private const val BOX = 0xFFF1F7F9.toInt()
    private const val LINE = 0xFFD6E3E8.toInt()

    fun fileName(ctx: Context, key: RecoveryKey): String =
        ctx.getString(R.string.backup_kit_file_name, key.shortId)

    fun write(ctx: Context, key: RecoveryKey, createdMs: Long, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            draw(ctx, key, createdMs, page.canvas)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun draw(ctx: Context, key: RecoveryKey, createdMs: Long, c: Canvas) {
        val right = PAGE_W - M
        val width = right - M

        drawLogo(ctx, c, RectF(M, M, M + 40f, M + 40f))
        c.drawText("SinusChat", M + 52f, M + 19f, text(21f, INK, bold = true))
        c.drawText(ctx.getString(R.string.backup_kit_subtitle), M + 52f, M + 37f, text(11.5f, MUTED))
        c.drawText(ctx.getString(R.string.backup_kit_created), right, M + 14f,
            text(9f, MUTED).apply { textAlign = Paint.Align.RIGHT })
        c.drawText(date(ctx, createdMs), right, M + 31f,
            text(11f, INK, bold = true).apply { textAlign = Paint.Align.RIGHT })
        c.drawLine(M, 106f, right, 106f, stroke(LINE, 1f))

        var y = 124f
        y = paragraph(c, ctx.getString(R.string.backup_kit_intro), M, y, width, text(11f, INK))
        val email = SetupActivity.getUserEmail(ctx)
        if (pl.sinusoide.sinuschat.setup.AnonAccount.isAnonId(email) || pl.sinusoide.sinuschat.setup.AnonAccount.creating)
            y = paragraph(c, ctx.getString(R.string.backup_kit_anon), M, y + 6f, width, text(10f, INK, bold = true))
        else if (email.isNotEmpty())
            y = paragraph(c, ctx.getString(R.string.backup_kit_account, email), M, y + 6f, width, text(10f, MUTED))

        val boxTop = y + 18f
        val boxH = 240f
        val box = RectF(M, boxTop, right, boxTop + boxH)
        c.drawRoundRect(box, 14f, 14f, fill(BOX))
        c.drawRoundRect(box, 14f, 14f, stroke(LINE, 1f))

        val qr = 172f
        val qrLeft = M + 26f
        val qrTop = boxTop + 26f
        c.drawRoundRect(RectF(qrLeft - 10f, qrTop - 10f, qrLeft + qr + 10f, qrTop + qr + 10f), 10f, 10f, fill(Color.WHITE))
        drawQr(c, key.qrPayload(), qrLeft, qrTop, qr)
        c.drawText(ctx.getString(R.string.backup_kit_qr_label), qrLeft + qr / 2f, qrTop + qr + 28f,
            text(9f, MUTED).apply { textAlign = Paint.Align.CENTER })

        val wordsLeft = qrLeft + qr + 36f
        c.drawText(ctx.getString(R.string.backup_kit_words_label).uppercase(), wordsLeft, boxTop + 36f,
            text(8.5f, ACCENT, bold = true).apply { letterSpacing = 0.08f })
        val words = key.words(ctx)
        val colW = (right - 16f - wordsLeft) / 2f
        val number = text(10f, MUTED).apply { textAlign = Paint.Align.RIGHT }
        val word = text(15f, INK, bold = true)
        val underline = stroke(LINE, 0.8f)
        for (i in words.indices) {
            val x = wordsLeft + (i / 6) * colW
            val base = boxTop + 70f + (i % 6) * 29f
            c.drawText("${i + 1}", x + 14f, base, number)
            c.drawText(words[i], x + 22f, base, word)
            c.drawLine(x + 22f, base + 7f, x + colW - 12f, base + 7f, underline)
        }

        y = boxTop + boxH + 26f
        c.drawText(ctx.getString(R.string.backup_kit_id, key.shortId), M, y, text(11f, INK, bold = true))
        y = paragraph(c, ctx.getString(R.string.backup_kit_id_hint), M, y + 7f, width, text(10f, MUTED))

        y = paragraph(c, ctx.getString(R.string.backup_kit_how_title), M, y + 22f, width, text(13f, INK, bold = true))
        y += 4f
        for ((i, res) in listOf(R.string.backup_kit_how_1, R.string.backup_kit_how_2,
            R.string.backup_kit_how_3).withIndex())
            y = item(c, "${i + 1}.", ctx.getString(res), y + 5f, width)

        y = paragraph(c, ctx.getString(R.string.backup_kit_keep_title), M, y + 20f, width, text(13f, INK, bold = true))
        y += 4f
        for (res in listOf(R.string.backup_kit_keep_1, R.string.backup_kit_keep_2, R.string.backup_kit_keep_3,
            R.string.backup_kit_keep_4))
            y = item(c, "•", ctx.getString(res), y + 5f, width)

        val foot = PAGE_H - M + 6f
        c.drawLine(M, foot - 16f, right, foot - 16f, stroke(LINE, 1f))
        c.drawText("SinusChat · sinusoide.pl", M, foot, text(9f, MUTED))
        c.drawText(ctx.getString(R.string.backup_kit_id, key.shortId), right, foot,
            text(9f, MUTED).apply { textAlign = Paint.Align.RIGHT })
    }

    private fun item(c: Canvas, mark: String, body: String, top: Float, width: Float): Float {
        paragraph(c, mark, M, top, 16f, text(10.5f, ACCENT, bold = true))
        return paragraph(c, body, M + 18f, top, width - 18f, text(10.5f, INK))
    }

    private fun paragraph(c: Canvas, s: String, x: Float, top: Float, width: Float, paint: TextPaint): Float {
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt())
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()
        c.save()
        c.translate(x, top)
        layout.draw(c)
        c.restore()
        return top + layout.height
    }

    private fun drawQr(c: Canvas, payload: String, left: Float, top: Float, size: Float) {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 0
        )
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = m.width
        val cell = size / n
        val path = Path()
        for (row in 0 until n) {
            var col = 0
            while (col < n) {
                if (!m.get(col, row)) { col++; continue }
                val start = col
                while (col < n && m.get(col, row)) col++
                path.addRect(left + start * cell, top + row * cell,
                    left + col * cell, top + (row + 1) * cell + 0.05f, Path.Direction.CW)
            }
        }
        c.drawPath(path, fill(Color.BLACK))
    }

    private fun drawLogo(ctx: Context, c: Canvas, tile: RectF) {
        c.drawRoundRect(tile, tile.width() * 0.26f, tile.width() * 0.26f, fill(Color.BLACK))
        val inner = RectF(tile).apply { inset(tile.width() * 0.17f, tile.height() * 0.17f) }
        val src = pl.sinusoide.sinuschat.ui.SinusLogo.SINUSOIDE_BOUNDS
        val m = android.graphics.Matrix().apply { setRectToRect(src, inner, android.graphics.Matrix.ScaleToFit.CENTER) }
        c.drawPath(pl.sinusoide.sinuschat.ui.SinusLogo.sinusoide().apply { transform(m) }, fill(pl.sinusoide.sinuschat.ui.SinusLogo.CYAN))
    }

    private fun date(ctx: Context, ms: Long): String {
        val locale = ctx.resources.configuration.locales[0]
        return SimpleDateFormat("d MMMM yyyy", locale).format(Date(if (ms > 0L) ms else System.currentTimeMillis()))
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }
}
