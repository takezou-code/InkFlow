package com.vic.inkflow.data

/**
 * The tablet's Room rows ↔ the shared document model.
 *
 * ## Which direction, and why
 *
 * Only **model → row** is needed on the tablet today: the tablet is the writer,
 * so its rows are the source of truth and it emits them directly (the wire bytes
 * are the Room rows' field names — see `ModelWireTest`, which pins that the row
 * and the model serialise to the same JSON). What the tablet *receives* — a
 * desktop's proposal — arrives as the shared model and has to become a row
 * before it can be written to the database.
 *
 * The desktop has both directions: it receives documents (model → row) and it
 * emits its own strokes (row → model).
 *
 * ## The mapping is field-for-field, on purpose
 *
 * Every field is copied explicitly rather than by reflection or by a generic
 * mapper. That is more typing, and it is the point: a field added to one side
 * and forgotten on the other becomes a **compile error** here instead of a
 * silent difference on the wire.
 */
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
    // The wire may simply not carry the tool (the desktop has no column for it),
    // and the column is NOT NULL. The storage default is applied here — the one
    // place that knows what the column's default is.
    toolKind = toolKind ?: Stroke.TOOL_PEN,
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

fun ImageAnnotation.toRow(): ImageAnnotationEntity = ImageAnnotationEntity(
    id = id,
    documentUri = documentUri,
    pageIndex = pageIndex,
    docY = docY,
    uri = uri,
    modelX = modelX,
    modelY = modelY,
    modelWidth = modelWidth,
    modelHeight = modelHeight,
    rotation = rotation
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
