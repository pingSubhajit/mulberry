package com.subhajit.mulberry.drawing.model

enum class DrawingOperationType {
    ADD_STROKE,
    APPEND_POINTS,
    FINISH_STROKE,
    DELETE_STROKE,
    CLEAR_CANVAS,
    ADD_TEXT_ELEMENT,
    UPDATE_TEXT_ELEMENT,
    DELETE_TEXT_ELEMENT,
    ADD_STICKER_ELEMENT,
    UPDATE_STICKER_ELEMENT,
    DELETE_STICKER_ELEMENT,
    ADD_PHOTO_ELEMENT,
    UPDATE_PHOTO_ELEMENT,
    DELETE_PHOTO_ELEMENT
}

/**
 * Operations that put something of the person's own on the canvas, as the server counts them
 * toward the streak and the account's first drawing.
 */
val DrawingOperationType.drawsSomething: Boolean
    get() = when (this) {
        DrawingOperationType.FINISH_STROKE,
        DrawingOperationType.ADD_TEXT_ELEMENT,
        DrawingOperationType.UPDATE_TEXT_ELEMENT,
        DrawingOperationType.ADD_STICKER_ELEMENT,
        DrawingOperationType.UPDATE_STICKER_ELEMENT,
        DrawingOperationType.ADD_PHOTO_ELEMENT,
        DrawingOperationType.UPDATE_PHOTO_ELEMENT -> true
        DrawingOperationType.ADD_STROKE,
        DrawingOperationType.APPEND_POINTS,
        DrawingOperationType.DELETE_STROKE,
        DrawingOperationType.CLEAR_CANVAS,
        DrawingOperationType.DELETE_TEXT_ELEMENT,
        DrawingOperationType.DELETE_STICKER_ELEMENT,
        DrawingOperationType.DELETE_PHOTO_ELEMENT -> false
    }
