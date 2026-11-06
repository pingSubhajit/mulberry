package com.subhajit.mulberry.share

import java.io.File
import java.lang.reflect.Method

/**
 * Measures text with java.awt, which the test JVM has but the Android unit-test classpath hides,
 * so it's reached by reflection. Advances use fractional metrics, without hinting or kerning.
 */
class AwtTextMeasurer(fontFile: File) : ShareTextMeasurer {
    private val fontClass: Class<*> = Class.forName("java.awt.Font")
    private val renderContextClass: Class<*> = Class.forName("java.awt.font.FontRenderContext")
    private val deriveFont: Method = fontClass.getMethod("deriveFont", Float::class.javaPrimitiveType)
    private val getStringBounds: Method = fontClass.getMethod("getStringBounds", String::class.java, renderContextClass)
    private val getWidth: Method = Class.forName("java.awt.geom.RectangularShape").getMethod("getWidth")

    private val baseFont: Any = checkNotNull(
        fontClass.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
            .invoke(null, TRUETYPE_FONT, fontFile)
    )
    private val renderContext: Any = renderContextClass
        .getConstructor(
            Class.forName("java.awt.geom.AffineTransform"),
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        .newInstance(null, true, true)
    private val sizedFonts = HashMap<Int, Any>()

    override fun advanceWidth(text: String, fontSize: Int): Double {
        val font = sizedFonts.getOrPut(fontSize) { checkNotNull(deriveFont.invoke(baseFont, fontSize.toFloat())) }
        return getWidth.invoke(getStringBounds.invoke(font, text, renderContext)) as Double
    }

    private companion object {
        const val TRUETYPE_FONT = 0
    }
}
