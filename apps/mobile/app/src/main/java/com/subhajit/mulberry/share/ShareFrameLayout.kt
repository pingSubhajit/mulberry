package com.subhajit.mulberry.share

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A share frame's layout file (docs/share-frames.md, "Layout schema"). Every coordinate is in the
 * 1080×1920 output.
 */
data class ShareFrameLayout(
    val layoutVersion: Int,
    val key: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val content: ShareFrameContent,
    val caption: ShareFrameCaption,
    val brand: ShareFrameBrand
)

data class SharePoint(val x: Double, val y: Double)

/** Where the corners of the content image go. An affine image of the content rectangle. */
data class ShareQuad(
    val topLeft: SharePoint,
    val topRight: SharePoint,
    val bottomRight: SharePoint,
    val bottomLeft: SharePoint
)

enum class ShareContentKind(val wireValue: String) {
    PHONE("phone"),
    POLAROID("polaroid")
}

data class ShareFrameContent(
    val kind: ShareContentKind,
    val aspectRatio: Double,
    val quad: ShareQuad
)

enum class ShareBlockAnchor(val wireValue: String) {
    TOP_LEFT("topLeft"),
    TOP_RIGHT("topRight"),
    BOTTOM_LEFT("bottomLeft"),
    BOTTOM_RIGHT("bottomRight"),
    CENTER("center")
}

enum class ShareCaptionAlign(val wireValue: String) {
    LEFT("left"),
    RIGHT("right")
}

data class ShareBacking(
    val colorArgb: Int,
    val paddingX: Double,
    val paddingY: Double,
    val cornerRadius: Double = 0.0
)

data class ShareFrameCaption(
    val position: SharePoint,
    val anchor: ShareBlockAnchor,
    val rotationDegrees: Double,
    val maxWidth: Double,
    val maxLines: Int,
    val maxFontSize: Int,
    val minFontSize: Int,
    val lineHeight: Double,
    val align: ShareCaptionAlign,
    val inkColorArgb: Int,
    val backing: ShareBacking?
)

data class ShareFrameBrand(
    val position: SharePoint,
    val anchor: ShareBlockAnchor,
    val rotationDegrees: Double,
    val markHeight: Double,
    val fontSize: Double,
    val colorArgb: Int,
    val backing: ShareBacking?
)

object ShareFrameLayoutParser {
    /** The only layout version this app draws. Frames with any other version are skipped. */
    const val SUPPORTED_LAYOUT_VERSION = 1

    fun parse(json: String): ShareFrameLayout? =
        runCatching { JsonParser.parseString(json) }.getOrNull()?.let(::parse)

    /** The layout, or null when it's malformed or uses a layout version this app can't draw. */
    fun parse(element: JsonElement?): ShareFrameLayout? =
        runCatching { parseOrNull(element) }.getOrNull()

    private fun parseOrNull(element: JsonElement?): ShareFrameLayout? {
        val root = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val layoutVersion = root.int("layoutVersion") ?: return null
        if (layoutVersion != SUPPORTED_LAYOUT_VERSION) return null
        val key = root.string("key")?.takeIf { it.isNotBlank() } ?: return null
        val canvas = root.obj("canvas") ?: return null
        val content = root.obj("content")?.let(::parseContent) ?: return null
        val caption = root.obj("caption")?.let(::parseCaption) ?: return null
        val brand = root.obj("brand")?.let(::parseBrand) ?: return null
        return ShareFrameLayout(
            layoutVersion = layoutVersion,
            key = key,
            canvasWidth = canvas.int("width") ?: return null,
            canvasHeight = canvas.int("height") ?: return null,
            content = content,
            caption = caption,
            brand = brand
        )
    }

    private fun parseContent(content: JsonObject): ShareFrameContent? {
        val kind = content.string("kind")
            ?.let { wire -> ShareContentKind.entries.firstOrNull { it.wireValue == wire } }
            ?: return null
        val quad = content.obj("quad") ?: return null
        return ShareFrameContent(
            kind = kind,
            aspectRatio = content.double("aspectRatio") ?: return null,
            quad = ShareQuad(
                topLeft = quad.point("topLeft") ?: return null,
                topRight = quad.point("topRight") ?: return null,
                bottomRight = quad.point("bottomRight") ?: return null,
                bottomLeft = quad.point("bottomLeft") ?: return null
            )
        )
    }

    private fun parseCaption(caption: JsonObject): ShareFrameCaption? {
        val maxFontSize = caption.int("maxFontSize") ?: return null
        val minFontSize = caption.int("minFontSize") ?: return null
        val maxLines = caption.int("maxLines") ?: return null
        if (minFontSize <= 0 || maxFontSize < minFontSize || maxLines <= 0) return null
        return ShareFrameCaption(
            position = caption.point("position") ?: return null,
            anchor = caption.anchor() ?: return null,
            rotationDegrees = caption.double("rotationDegrees") ?: 0.0,
            maxWidth = caption.double("maxWidth")?.takeIf { it > 0 } ?: return null,
            maxLines = maxLines,
            maxFontSize = maxFontSize,
            minFontSize = minFontSize,
            lineHeight = caption.double("lineHeight") ?: return null,
            align = caption.string("align")
                ?.let { wire -> ShareCaptionAlign.entries.firstOrNull { it.wireValue == wire } }
                ?: return null,
            inkColorArgb = caption.string("inkColor")?.let(::parseHexColor) ?: return null,
            backing = caption.backing("backing")
        )
    }

    private fun parseBrand(brand: JsonObject): ShareFrameBrand? {
        return ShareFrameBrand(
            position = brand.point("position") ?: return null,
            anchor = brand.anchor() ?: return null,
            rotationDegrees = brand.double("rotationDegrees") ?: 0.0,
            markHeight = brand.double("markHeight") ?: return null,
            fontSize = brand.double("fontSize") ?: return null,
            colorArgb = brand.string("color")?.let(::parseHexColor) ?: return null,
            backing = brand.backing("backing")
        )
    }

    private fun JsonObject.backing(name: String): ShareBacking? {
        val backing = obj(name) ?: return null
        return ShareBacking(
            colorArgb = backing.string("color")?.let(::parseHexColor) ?: return null,
            paddingX = backing.double("paddingX") ?: 0.0,
            paddingY = backing.double("paddingY") ?: 0.0,
            cornerRadius = backing.double("cornerRadius") ?: 0.0
        )
    }

    private fun JsonObject.anchor(): ShareBlockAnchor? = string("anchor")
        ?.let { wire -> ShareBlockAnchor.entries.firstOrNull { it.wireValue == wire } }

    private fun JsonObject.point(name: String): SharePoint? {
        val point = obj(name) ?: return null
        return SharePoint(x = point.double("x") ?: return null, y = point.double("y") ?: return null)
    }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.primitive(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.string(name: String): String? =
        primitive(name)?.takeIf { it.isString }?.asString

    private fun JsonObject.double(name: String): Double? =
        primitive(name)?.takeIf { it.isNumber }?.asDouble?.takeIf { it.isFinite() }

    private fun JsonObject.int(name: String): Int? =
        double(name)?.takeIf { it == Math.floor(it) }?.toInt()
}

/** `#RRGGBB` as an opaque ARGB int, without android.graphics so it's testable on the JVM. */
internal fun parseHexColor(value: String): Int? {
    val hex = value.removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return (0xFF000000.toInt()) or hex.toInt(16)
}
