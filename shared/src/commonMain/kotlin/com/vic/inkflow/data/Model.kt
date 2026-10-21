package com.vic.inkflow.data

/**
 * The document model: **one** shape for a document, shared by every platform.
 *
 * ## Why this exists
 *
 * The tablet and the desktop each declared their own copy of the same concepts —
 * `StrokeEntity` with `@Entity` on one side, a plain data class with a JDBC
 * mapping on the other — and the copies had already drifted:
 *
 *  - the tablet's stroke has a `toolKind` column, the desktop's does not;
 *  - the tablet's `TextAnnotationEntity` defaults its colour to
 *    `android.graphics.Color.BLACK` — an **Android API inside a data class
 *    default**, so the "shared" model could not even be compiled for iOS;
 *  - `StrokeEntity` was 82 lines on one side and 48 on the other.
 *
 * The wire format is the contract, and the wire format is these field names. So
 * the field names belong to a type that is written down **once**, with no
 * platform annotations and no platform APIs, and every platform's storage is a
 * *mapping* of it rather than a second opinion about what a stroke is.
 *
 * ## What this type is not
 *
 * It is not a database row. Room on the tablet and JDBC on the desktop are
 * storage details — the desktop's own `DatabaseManager` says so out loud:
 * "the wire DTOs are the contract; the SQL on each side is an implementation
 * detail". This model sits one level above both: it is what the *code* reasons
 * about, and both storages map to it.
 *
 * ## The field names are the wire
 *
 * The wire carries these objects as JSON with exactly these field names (the
 * payload of `stroke_page` / `text_page` / `document_detail` is a JSON array of
 * them). That is deliberate: a model whose names matched the wire can be
 * deserialised directly, so the "translate the payload" step — which is where
 * the two sides used to disagree — disappears.
 *
 * Consequence: **renaming a field here is a wire change.** Add a field with a
 * default instead, and bump `PROTOCOL_VERSION` if the peer must reject the old
 * shape (see `docs/specs/sync.md`).
 *
 * ## No clocks, no UUIDs, no platform APIs
 *
 * `id` is required and the timestamps default to 0: a model type that generates
 * its own ids or reads the clock is a model type that cannot be compared, and
 * `java.util.UUID` / `System.currentTimeMillis()` do not exist on iOS anyway.
 * The platform decides when an id is minted and what "now" is.
 */
data class Document(
    val uri: String,
    val displayName: String,
    /** Epoch millis. Display-only for the diff — the diff uses [docVersion]. */
    val lastOpenedAt: Long = 0L,
    /** Resume position. */
    val lastPageIndex: Int = 0,
    val isFavorite: Boolean = false,
    val folderId: String? = null
)

/** A category folder. Synced as a flat list (the tree is rebuilt from [parentFolderId]). */
data class Folder(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * A stroke's metadata, without its points.
 *
 * The bounding box is cached on the row: broad-phase collision detection would
 * otherwise have to load every point of every stroke on the page.
 */
data class Stroke(
    val id: String,
    /** Scopes this stroke to a document. */
    val documentUri: String,
    val pageIndex: Int,
    /**
     * Document-space anchor: `pageIndex × stride + boundsTop`.
     *
     * Null = not backfilled yet (pre-v24 data). The tablet backfills lazily with
     * the live page height; guessing a stride on the receiving side puts strokes
     * in the wrong place, so this stays nullable everywhere.
     */
    val docY: Float? = null,
    val color: Int,
    val strokeWidth: Float,
    val boundsLeft: Float = 0f,
    val boundsTop: Float = 0f,
    val boundsRight: Float = 0f,
    val boundsBottom: Float = 0f,
    /** true = multiply-blended highlighter, false = normal pen. */
    val isHighlighter: Boolean = false,
    /**
     * Which tool drew this ("PEN" / "HIGHLIGHTER" / "CRAYON").
     *
     * The tablet keeps a crayon in its schema even when the tool is retired, so
     * that Room's schema identity check keeps matching the databases already on
     * people's tablets. It is carried here so the shape does not drift again.
     *
     * **Nullable on purpose**: the desktop's tables have no such column, so the
     * payload it sends simply does not carry the field — and Gson, which fills
     * fields by reflection, does not run Kotlin's default values, so a missing
     * field arrives as null rather than as "PEN". The *storage* default belongs
     * to the storage mapping (see the tablet's `ModelMapping.kt`), which is the
     * one place that knows what the column's default is.
     *
     * Before this was spelled out, a stroke proposed by the desktop arrived with
     * a null `toolKind` and was written into a NOT NULL column — a proposal that
     * could only ever fail.
     */
    val toolKind: String? = null,
    /**
     * null = freehand stroke.
     * "RECT" | "CIRCLE" | "LINE" | "ARROW" = geometric shape.
     */
    val shapeType: String? = null,
    /**
     * Change counter, bumped on every rewrite. A counter, not a clock: two
     * devices' clocks are never synchronised, and a merge rule that needs them
     * to agree is a merge rule that silently overwrites.
     */
    val version: Int = 1,
    /** Tiebreak for equal [version]: smaller wins. Deterministic on both sides. */
    val versionNonce: Int = 0,
    /** Tombstone: non-null = deleted. Null = alive. */
    val deletedAt: Long? = null
) {
    companion object {
        const val TOOL_PEN = "PEN"
        const val TOOL_HIGHLIGHTER = "HIGHLIGHTER"
        const val TOOL_CRAYON = "CRAYON"
    }
}

/**
 * One sample of a stroke.
 *
 * The **order** of the points is the polyline order — it is not a set.
 */
data class Point(
    val id: Long = 0L,
    val strokeId: String,
    val x: Float,
    val y: Float,
    /** Per-sample width (velocity-driven thickness). */
    val width: Float = 0f
)

/**
 * A stroke together with its ordered points — the shape a stroke has **on the wire**
 * (`{"stroke": …, "points": […]}`).
 *
 * Named `…WithSamples` rather than `…WithPoints` on purpose: both platforms already
 * have a storage aggregate called `StrokeWithPoints` (Room's `@Embedded` + `@Relation`
 * on the tablet, a JDBC-mapped one on the desktop). Two types with the same simple
 * name in the same package — one from `:shared`, one from the platform module —
 * would shadow each other, and the shadowed one would be unreachable by name. The
 * shape is identical; only the name differs, and the name is not on the wire.
 */
data class StrokeWithSamples(
    val stroke: Stroke,
    val points: List<Point>
)

/**
 * A text (or stamp) annotation on a page.
 *
 * `modelX` / `modelY` are in model space (595 × 842 PDF points).
 *
 * **`modelY` is the first line's baseline, not the top-left corner.** Every
 * bounding-box calculation has to subtract the ascent to get a top edge; using
 * `modelY` as a top edge puts every label about one font-size too low, which is
 * the classic "the text is there but the hit box missed it" bug — you can see it
 * on screen and still be unable to select it.
 */
data class TextAnnotation(
    val id: String,
    val documentUri: String,
    val pageIndex: Int,
    /** Document-space anchor (`pageIndex × stride + modelY`); null = not backfilled. */
    val docY: Float? = null,
    val text: String,
    val modelX: Float,
    val modelY: Float,
    /** Font size in model-space points (same scale as [Stroke.strokeWidth]). */
    val fontSize: Float = 16f,
    val colorArgb: Int = 0xFF000000.toInt(),
    /** true = oversized emoji stamp, false = regular text label. */
    val isStamp: Boolean = false,
    /** Text-box wrap width in model units; null = old rows that break only on `\n`. */
    val boxWidth: Float? = null,
    /** 0 = left, 1 = centre, 2 = right. */
    val alignment: Int = 0,
    val version: Int = 1,
    val versionNonce: Int = 0,
    val deletedAt: Long? = null
)

/**
 * An image annotation on a page. Coordinates in model space.
 *
 * [uri] points at the image file; on Android it is a `content://` or `file://`
 * URI from the media picker, on the desktop a local path.
 */
data class ImageAnnotation(
    val id: String,
    val documentUri: String,
    val pageIndex: Int,
    /** Document-space anchor (`pageIndex × stride + modelY`); null = not backfilled. */
    val docY: Float? = null,
    val uri: String,
    val modelX: Float,
    val modelY: Float,
    val modelWidth: Float,
    val modelHeight: Float,
    /** Clockwise degrees about the image centre. Scale-free. */
    val rotation: Float = 0f
)
