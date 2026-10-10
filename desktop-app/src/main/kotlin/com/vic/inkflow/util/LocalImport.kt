package com.vic.inkflow.util

import java.io.File
import java.security.MessageDigest

/**
 * Opening a local PDF.
 *
 * ## Why this exists at all
 *
 * The desktop can only open documents that arrived over sync, because `uri` is the
 * sync identity and a local file has none. That made the app unusable on its own:
 * a user who has PDFs on their machine could not open one without the tablet
 * running.
 *
 * ## The one rule that matters
 *
 * Identity comes from the **full path**, never from the file name.
 *
 * `lecture.pdf` in two folders is two different documents with two different sets
 * of annotations. Keying on the basename makes the second import collide with the
 * first, and because the library upserts by `uri`, the second one silently takes
 * over the row — so the first document's notes appear attached to the second file.
 * That is silent data corruption from the user's point of view: nothing errors,
 * the annotations are simply on the wrong page.
 *
 * ## Mirror, not link
 *
 * The file is **copied** into the app directory. A link breaks the moment the user
 * moves or deletes the original, and the document then cannot be opened at all —
 * which looks like the app losing the file. The copy costs disk once and makes the
 * library independent of where the user keeps their files.
 *
 * ## docY stays null
 *
 * Mirroring a PDF copies bytes, not annotations, so the imported document starts
 * with no strokes and no notes. Nothing here writes `docY`; as everywhere else on
 * the desktop, a NULL means "the tablet backfills this when it opens the document"
 * and guessing a stride here would place content on the wrong page.
 */
object LocalImport {

    /** Matches `PdfManager`'s cap: an unreadable file should not enter the library. */
    const val MAX_FILE_BYTES = 512L * 1024 * 1024

    /** `C:\a\lecture.pdf` -> `file:///C:/a/lecture.pdf` */
    fun toDocumentUri(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("file://") || trimmed.contains("://")) return trimmed
        val normalised = trimmed.replace('\\', '/')
        return if (normalised.startsWith("/")) {
            "file://$normalised"
        } else {
            "file:///$normalised"
        }
    }

    /**
     * Is [candidate] inside [root] (or the root itself)?
     *
     * 用 canonicalPath 比對，不做字串 prefix：字串比對會被 `../` 逃逸騙過
     * （`~/.inkflow/documents/../../ssh/id_rsa` 的字串確實以 documents 開頭），
     * 也擋不住指向目外的符號連結/junction。canonical 會把這些全部解析掉。
     *
     * 純函式、無副作用，所以可以直接釘單測——這道閘是「已認證的 client 不能讀
     * 桌面任意檔案」的唯一保證。
     */
    fun isInsideDir(root: File, candidate: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        val rootPath = canonicalRoot.path
        val candidatePath = canonicalCandidate.path
        if (candidatePath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return candidatePath.startsWith(prefix)
    }

    /**
     * Inverse of [toDocumentUri]. Null for anything that is not a local file.
     *
     * The leading slash is stripped deliberately: `file:///C:/x` has three slashes
     * and only the first two belong to the scheme, so the remaining `/C:/x` has to
     * lose its separator before it names a real path. Getting this wrong yields
     * `\C:\Users\...`, which does not exist — and the failure surfaces much later
     * as "file not found" on a file the user can plainly see.
     */
    fun fromDocumentUri(uri: String): String? {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("file://")) return null
        var rest = trimmed.removePrefix("file://")
        // UNC paths (\\server\share) legitimately keep their leading slashes; only
        // strip for the drive-letter form.
        if (rest.length > 2 && rest[0] == '/' && rest[2] == ':') rest = rest.substring(1)
        return rest.replace('/', File.separatorChar)
    }

    /**
     * A stable id for a uri.
     *
     * Derived rather than random because sync compares identities across launches.
     * A fresh UUID per import would make re-importing the same file look like two
     * documents.
     */
    fun suggestedId(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.trim().lowercase().toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** File name without extension, for display in the library. */
    fun displayName(pathOrUri: String): String {
        val name = File(pathOrUri.replace('/', File.separatorChar)).name
        val dot = name.lastIndexOf('.')
        // A leading dot is part of the name (".gitignore"), not an extension.
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /**
     * Where a copy of this document should live in the mirror.
     *
     * The prefix keeps two same-named files apart: `<hash>_<name>.pdf`.
     */
    fun mirrorTargetFor(uri: String, source: File, mirrorRoot: File): File {
        val prefix = suggestedId(uri).take(8)
        val name = source.name
        return File(mirrorRoot, "${prefix}_$name")
    }

    /** What an import would do, before it does it. */
    data class Plan(
        val uri: String,
        val displayName: String,
        val source: File,
        val target: File,
        val alreadyPresent: Boolean
    )

    /**
     * Decide the identity and destination for [source]. Performs no I/O beyond
     * checking whether the target exists, so a caller can present the plan first.
     */
    fun plan(uri: String, source: File, mirrorRoot: File): Plan {
        val target = mirrorTargetFor(uri, source, mirrorRoot)
        return Plan(
            uri = uri,
            displayName = displayName(source.name),
            source = source,
            target = target,
            alreadyPresent = target.isFile
        )
    }

    /** Result of a validation step: either the value, or a user-facing reason. */
    sealed interface Check<out T> {
        data class Ok<T>(val value: T) : Check<T>
        data class Rejected(val reason: String) : Check<Nothing>

        val isOk: Boolean get() = this is Ok
        fun valueOrNull(): T? = (this as? Ok)?.value

        /** Why the check failed, or null when it passed. */
        val message: String? get() = (this as? Rejected)?.reason
    }

    fun checkExists(file: File): Check<File> =
        if (file.isFile) Check.Ok(file)
        else Check.Rejected("找不到檔案：${file.name}")

    fun checkExtension(fileName: String): Check<String> {
        val lower = fileName.lowercase()
        return if (lower.endsWith(".pdf")) Check.Ok(fileName)
        else Check.Rejected("只支援 PDF，選到的是 $fileName")
    }

    fun checkSize(sizeBytes: Long, maxBytes: Long = MAX_FILE_BYTES): Check<Long> =
        if (sizeBytes <= 0) Check.Rejected("檔案是空的")
        else if (sizeBytes > maxBytes) {
            val mb = maxBytes / (1024 * 1024)
            Check.Rejected("檔案超過 ${mb}MB 上限，無法開啟")
        } else Check.Ok(sizeBytes)

    /**
     * Copy [source] to [target], or keep the existing copy.
     *
     * Refuses to overwrite a *different* file that already sits at the target:
     * with two files sharing a basename that is a real possibility, and silently
     * replacing one document's PDF with another's is exactly the data-loss bug
     * this whole file exists to prevent.
     */
    fun copyIntoMirror(plan: Plan): Check<File> {
        plan.target.parentFile?.mkdirs()
        if (plan.target.isFile) {
            val sameLength = plan.target.length() == plan.source.length()
            return if (sameLength) Check.Ok(plan.target)
            else Check.Rejected("目標已有不同檔案，未覆寫：${plan.target.name}")
        }
        return runCatching { plan.source.copyTo(plan.target, overwrite = false); Check.Ok(plan.target) }
            .getOrElse { Check.Rejected("複製失敗：${it.message ?: it::class.simpleName}") }
    }
}