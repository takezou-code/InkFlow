package com.vic.inkflow.data

import com.vic.inkflow.data.Document
import com.vic.inkflow.data.Folder
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation

/**
 * The desktop's JDBC rows ↔ the shared document model.
 *
 * ## What this is for
 *
 * The wire carries the shared model (`:shared`'s `com.vic.inkflow.data`), field for
 * field. The desktop stores it in its own SQLite tables, whose row classes are a
 * *storage* decision — the desktop's `DatabaseManager` says so out loud: "the wire
 * DTOs are the contract; the SQL on each side is an implementation detail".
 *
 * So the desktop's rows are the storage mapping of the model, not a second opinion
 * about what a stroke is. These functions are the seam between the two.
 *
 * ## The one field the desktop does not store
 *
 * `toolKind` (which tool drew the stroke) exists in the model and on the tablet,
 * but the desktop's tables have no column for it — it was added on the tablet
 * (DB v28) and never mirrored. That is exactly the kind of drift this model is
 * meant to make visible, so it is **not** papered over here: the desktop's rows
 * simply do not carry it, and the model keeps it.
 *
 * Nothing breaks, because the tablet's current build does not read `toolKind`
 * either ("this build does not read it" — it is kept only so Room's schema
 * identity check keeps matching the databases already on people's tablets). If the
 * tablet ever starts reading it, the desktop's rows have to grow the column; that
 * is a deliberate change, and `ModelWireTest` on the desktop side is what will
 * point at it.
 *
 * The desktop also keeps its own `StrokeWithPoints` (the JDBC row aggregate) —
 * the shared model's aggregate is called `StrokeWithSamples` precisely so the two
 * can coexist in one package without shadowing each other.
 */

// ── wire model → desktop rows ───────────────────────────────────────────────

fun StrokeWithSamples.toRow(): StrokeWithPoints = StrokeWithPoints(
    stroke = stroke.toRow(),
    points = points.map { it.toRow() }
)

fun Stroke.toRow(): StrokeEntity = StrokeEntity(
    id = id,
    documentUri = documentUri,
    pageIndex = pageIndex,
    docY = docY,
    color = color,
    strokeWidth = strokeWidth,
    boundsLeft = boundsLeft,
    boundsTop = boundsTop,
    boundsRight = boundsRight,
    boundsBottom = boundsBottom,
    isHighlighter = isHighlighter,
    shapeType = shapeType,
    version = version,
    versionNonce = versionNonce,
    deletedAt = deletedAt
)

fun Point.toRow(): PointEntity = PointEntity(
    id = id,
    strokeId = strokeId,
    x = x,
    y = y,
    width = width
)

fun TextAnnotation.toRow(): TextAnnotationEntity = TextAnnotationEntity(
    id = id,
    documentUri = documentUri,
    pageIndex = pageIndex,
    docY = docY,
    text = text,
    modelX = modelX,
    modelY = modelY,
    fontSize = fontSize,
    colorArgb = colorArgb,
    isStamp = isStamp,
    boxWidth = boxWidth,
    alignment = alignment,
    version = version,
    versionNonce = versionNonce,
    deletedAt = deletedAt
)

fun Document.toRow(): DocumentEntity = DocumentEntity(
    uri = uri,
    displayName = displayName,
    lastOpenedAt = lastOpenedAt,
    lastPageIndex = lastPageIndex,
    isFavorite = isFavorite,
    folderId = folderId
)

fun Folder.toRow(): FolderEntity = FolderEntity(
    id = id,
    name = name,
    parentFolderId = parentFolderId,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// ── desktop rows → wire model ───────────────────────────────────────────────
//
// The desktop's own rows are the ones it *emits* (a proposal carries a stroke the
// desktop just drew). They are mapped back to the model so that the emitted
// payload is produced from the shared shape.
//
// Note the consequence: the emitted JSON is the model's JSON, and the model's
// `toolKind` default ("PEN") is included. The tablet ignores that field today,
// so the bytes change but the protocol does not break — see `ModelWireTest` on
// the desktop side, which pins exactly that difference.

fun StrokeWithPoints.toModel(): StrokeWithSamples = StrokeWithSamples(
    stroke = stroke.toModel(),
    points = points.map { it.toModel() }
)

fun StrokeEntity.toModel(): Stroke = Stroke(
    id = id,
    documentUri = documentUri,
    pageIndex = pageIndex,
    docY = docY,
    color = color,
    strokeWidth = strokeWidth,
    boundsLeft = boundsLeft,
    boundsTop = boundsTop,
    boundsRight = boundsRight,
    boundsBottom = boundsBottom,
    isHighlighter = isHighlighter,
    // The desktop has one tool; say so rather than leaving it absent, so the
    // payload the desktop emits carries the same field the tablet's does.
    toolKind = Stroke.TOOL_PEN,
    shapeType = shapeType,
    version = version,
    versionNonce = versionNonce,
    deletedAt = deletedAt
)

fun PointEntity.toModel(): Point = Point(
    id = id,
    strokeId = strokeId,
    x = x,
    y = y,
    width = width
)

fun TextAnnotationEntity.toModel(): TextAnnotation = TextAnnotation(
    id = id,
    documentUri = documentUri,
    pageIndex = pageIndex,
    docY = docY,
    text = text,
    modelX = modelX,
    modelY = modelY,
    fontSize = fontSize,
    colorArgb = colorArgb,
    isStamp = isStamp,
    boxWidth = boxWidth,
    alignment = alignment,
    version = version,
    versionNonce = versionNonce,
    deletedAt = deletedAt
)

fun DocumentEntity.toModel(): Document = Document(
    uri = uri,
    displayName = displayName,
    lastOpenedAt = lastOpenedAt,
    lastPageIndex = lastPageIndex,
    isFavorite = isFavorite,
    folderId = folderId
)

fun FolderEntity.toModel(): Folder = Folder(
    id = id,
    name = name,
    parentFolderId = parentFolderId,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt
)
