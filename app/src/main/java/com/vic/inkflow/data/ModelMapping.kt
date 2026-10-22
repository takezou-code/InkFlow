package com.vic.inkflow.data

/**
 * The tablet's Room rows ↔ the shared document model.
 *
 * ## Which direction, and why
 *
 * Both directions exist on the tablet:
 *
 *  - **model → row**: what the tablet *receives* (a desktop's proposal) arrives as
 *    the shared model and has to become a row before it can be written.
 *  - **row → model**: what the tablet *reads* (its own documents, its own strokes)
 *    leaves Room as rows and has to become the shared model, because that is what
 *    the shared editor core and the shared store interfaces speak.
 *
 * The wire bytes are the Room rows' field names (see `ModelWireTest`, which pins
 * that the row and the model serialise to the same JSON), which is what makes
 * this mapping a straight field-for-field copy instead of a translation.
 *
 * ## The mapping is field-for-field, on purpose
 *
 * Every field is copied explicitly rather than by reflection or by a generic
 * mapper. That is more typing, and it is the point: a field added to one side
 * and forgotten on the other becomes a **compile error** here instead of a
 * silent difference on the wire.
 */
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
    toolKind = toolKind,
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

fun ImageAnnotationEntity.toModel(): ImageAnnotation = ImageAnnotation(
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
    // The wire may simply not carry the tool (the desktop's rows have no such
    // column), and this column is NOT NULL. The storage default is applied
    // **here** — the one place that knows what the column's default is. Without
    // it, a stroke proposed by the desktop arrives with a null tool and the
    // insert fails on the constraint.
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
