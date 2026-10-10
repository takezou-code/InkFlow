package com.vic.inkflow.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 路徑閘：已認證的 client 不得讀到同步文件之外的任何檔案。
 *
 * 背景：`file_meta` / `file_data` 會把 client 傳來的 `documentUri` 當路徑用。
 * 桌面端原本的 fallback 是任意絕對路徑，於是同一個 WiFi 上、知道 8 位數配對碼的
 * 任何人都能送 `file:///C:/Users/<user>/.ssh/id_rsa` 並拿到內容。平板端一直有
 * `requireServableFile` 擋這件事，桌面端漏了。
 *
 * 這些案例釘的是**逃逸手法**，不是「某個路徑該不該通過」：prefix 比對最容易被
 * `../` 與 junction 騙過，而那正好是最需要擋的輸入。
 */
class ServablePathGateTest {

    private fun tmp(): File = Files.createTempDirectory("inkflow-gate").toFile()

    @Test
    fun aFileDirectlyInsideTheRootIsInside() {
        val root = tmp()
        val file = File(root, "a.pdf").apply { writeText("x") }
        assertTrue(LocalImport.isInsideDir(root, file))
    }

    @Test
    fun aFileInASubdirectoryIsInside() {
        val root = tmp()
        val nested = File(root, "sub/deeper").apply { mkdirs() }
        val file = File(nested, "a.pdf").apply { writeText("x") }
        assertTrue(LocalImport.isInsideDir(root, file))
    }

    @Test
    fun theRootItselfCountsAsInside() {
        // 邊界條件寫錯的話，`root/..` 這種剛好等於 root 的輸入會漏。
        val root = tmp()
        assertTrue(LocalImport.isInsideDir(root, root))
    }

    @Test
    fun aSiblingDirectoryWithAPrefixIsNotInside() {
        // 最典型的 prefix bug：documents-evil 的字串確實以 documents 開頭。
        val base = tmp()
        val root = File(base, "documents").apply { mkdirs() }
        val evil = File(base, "documents-evil").apply { mkdirs() }
        val file = File(evil, "a.pdf").apply { writeText("x") }
        assertFalse(LocalImport.isInsideDir(root, file))
    }

    @Test
    fun dotDotEscapeIsNotInside() {
        // 字串 prefix 會放行這個：canonical 不會。
        val base = tmp()
        val root = File(base, "documents").apply { mkdirs() }
        val outside = File(base, "secret.txt").apply { writeText("top secret") }
        val escaping = File(root, "../secret.txt")
        assertFalse(LocalImport.isInsideDir(root, escaping))
        assertTrue(outside.exists(), "前提：這個檔真的在根外面")
    }

    @Test
    fun deepDotDotEscapeIsNotInside() {
        val base = tmp()
        val root = File(base, "a/b/documents").apply { mkdirs() }
        val outside = File(base, "a/secret.txt").apply { writeText("x") }
        assertFalse(LocalImport.isInsideDir(root, File(root, "../../secret.txt")))
        assertFalse(LocalImport.isInsideDir(root, File(root, "../../a/secret.txt")))
        assertTrue(outside.exists())
    }

    @Test
    fun aMissingRootIsNeverInside() {
        // canonical 失敗時必須拒絕而不是放行——fail-open 是這種閘最糟的錯法。
        val root = File(tmp(), "does-not-exist")
        assertFalse(LocalImport.isInsideDir(root, File(tmp(), "a.pdf")))
    }

    @Test
    fun uriNormalisationRoundTripsThroughTheGate() {
        // 服務路徑的兩條合法來源都要能通過：鏡像內的、以及 documents 表登錄的本機檔。
        val base = tmp()
        val mirror = File(base, "documents").apply { mkdirs() }
        val mirrored = File(mirror, "lecture.pdf").apply { writeText("x") }
        val local = File(base, "user-docs/lecture.pdf").apply { parentFile.mkdirs(); writeText("x") }

        // 鏡像檔：走 documentsDir 分支
        assertTrue(LocalImport.isInsideDir(mirror, mirrorFileFor(mirror, "file:///anything/lecture.pdf")))
        // 本機匯入檔：不在 documentsDir，改由 documents 表白名單放行（此處只驗前半段）
        assertFalse(LocalImport.isInsideDir(mirror, local))
        assertTrue(mirrored.exists())
    }

    /** 鏡像檔名規則的獨立複述，避免測試依賴 LocalSyncManager 的私有方法。 */
    private fun mirrorFileFor(root: File, uri: String): File =
        File(root, File(uri.removePrefix("file://")).name)
}