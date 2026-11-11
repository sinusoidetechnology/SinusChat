package pl.sinusoide.sinuschat.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.PathParser

object SinusLogo {

    const val CYAN = 0xFF2AE0F5.toInt()

    val CONTENT = RectF(21f, 21f, 235f, 240f)

    private const val BUBBLE =
        "M49,21H206C222,21 235,34 235,50V163C235,180 223,192 207,192H91L49,233.5C42,240.5 31,239 25,232C22.5,229.2 21,226 21,222V49" +
            "C21,33.5 33.5,21 49,21ZM51,43H203C208.5,43 213,47.5 213,53V161C213,166.5 208.5,170.5 203,170.5H88.5" +
            "C82.5,170.5 78.3,173.2 74,177.3L43,208.5V51C43,46.5 46.5,43 51,43Z"

    private const val WAVE =
        "M45,382C74.15,473.2 97.69,560.8 133.94,649.29C142.08,669.16 150.86,689.08 165.21,705.04C191.26,733.99 235.64,749.91 270.5,736.76" +
            "C298.08,726.35 319.68,697.74 334.43,669.84C343.69,652.31 350.24,635.05 356.59,617.53C389.11,527.7 416.1,430.87 444.67,338.82" +
            "C456.83,299.65 469.27,261.34 488.82,222.24C493.09,213.68 497.71,205.08 503.43,197.29C516.34,179.74 534.85,166.31 551.17,167.79" +
            "C572.1,169.67 589.4,196.07 602.49,221.49C624.24,263.73 634.34,303.26 646.04,344.72C660.22,394.99 676.74,448.09 691.37,498.69" +
            "C704.46,543.98 716.05,587.27 734.28,632.67C752.54,678.12 777.46,725.67 822.93,738.4C840.88,743.42 862.03,743.02 879.85,735.73" +
            "C898.06,728.28 912.8,713.63 924.27,697.58C936.06,681.09 944.42,663.14 952.08,644.99C969.22,604.42 982.89,562.92 996.4,521.2" +
            "C1025.24,432.18 1053.37,342.18 1084.6,254.35L1030,255C1000.22,337.3 974.61,423.64 947.11,507.12C934.43,545.6 921.35,583.47 906.07,621.95" +
            "C902.88,629.98 899.6,638.04 895.46,646.03C888.07,660.29 877.96,674.33 864.83,681.3C853.37,687.4 839.59,688.1 828.87,682.9" +
            "C816.14,676.73 807.71,662.25 800.08,647.66C757.53,566.36 739.88,481.96 716.25,396.93C703.54,351.19 689.1,305.27 673.08,261.12" +
            "C666.62,243.32 659.9,225.8 652.89,208.21C646.33,191.74 639.52,175.2 628.68,160.22C607.27,130.64 570.17,107.14 536.92,110.95" +
            "C507.11,114.36 480.39,139.75 461.16,166.86C439.16,197.88 426.95,231.17 415.29,265.06C385.5,351.75 359.37,442.46 332.3,530.09" +
            "C320.72,567.55 308.98,604.45 290.51,641.71C285.6,651.61 280.22,661.55 272.84,669.1C260.02,682.21 241.2,688.13 226.14,682.99" +
            "C206.99,676.45 193.92,652.03 183.4,629.12C165.06,589.2 154.44,553.88 142.24,515.72C128.28,472.02 112.24,424.59 101.26,382.95L45,382Z"

    private val WAVE_POINTS = floatArrayOf(
        73f, 382f, 118f, 520f, 155f, 625f, 190f, 690f, 240f, 712f, 285f, 690f, 320f, 640f,
        360f, 540f, 410f, 390f, 455f, 260f, 500f, 175f, 545f, 140f, 595f, 170f, 640f, 260f,
        680f, 390f, 725f, 560f, 765f, 660f, 815f, 708f, 855f, 712f, 895f, 690f, 930f, 630f,
        965f, 530f, 1010f, 390f, 1057f, 255f
    )

    private val waveMatrix = Matrix().apply {
        setScale(0.158f, 0.158f)
        postTranslate(39.89f, 44.46f)
    }

    fun bubble(): Path = PathParser.createPathFromPathData(BUBBLE).apply { fillType = Path.FillType.EVEN_ODD }

    fun wave(): Path = PathParser.createPathFromPathData(WAVE).apply { transform(waveMatrix) }

    val SINUSOIDE_BOUNDS = RectF(45f, 111f, 1085f, 744f)

    fun sinusoide(): Path = PathParser.createPathFromPathData(WAVE)

    fun bubbleLine(): Path = Path().apply {
        moveTo(32f, 224f)
        lineTo(32f, 50f)
        cubicTo(32f, 39f, 39f, 32f, 50f, 32f)
        lineTo(205f, 32f)
        cubicTo(216f, 32f, 224f, 40f, 224f, 51f)
        lineTo(224f, 162f)
        cubicTo(224f, 174f, 215f, 181.25f, 205f, 181.25f)
        lineTo(90f, 181.25f)
        cubicTo(84f, 181.25f, 80f, 184f, 76f, 188f)
        lineTo(34f, 229f)
    }

    fun waveLine(): Path {
        val pts = (WAVE_POINTS.indices step 2).map { i ->
            PointF(39.89f + WAVE_POINTS[i] * 0.158f, 44.46f + WAVE_POINTS[i + 1] * 0.158f)
        }
        return Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[maxOf(i - 1, 0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[minOf(i + 2, pts.size - 1)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y
                )
            }
        }
    }

    fun fit(target: RectF): Matrix {
        val scale = minOf(target.width() / CONTENT.width(), target.height() / CONTENT.height())
        return Matrix().apply {
            setTranslate(-CONTENT.centerX(), -CONTENT.centerY())
            postScale(scale, scale)
            postTranslate(target.centerX(), target.centerY())
        }
    }
}
