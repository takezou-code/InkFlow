import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_tablet_call = '''                onDeletePage = { index ->
                    // DB 清理與 PDF 刪除平行執行，縮短總等待時間
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.strokeDao().clearPage(uri.toString(), index)
                        db.strokeDao().shiftPageIndicesDown(uri.toString(), index)
                        db.imageAnnotationDao().deleteForPage(uri.toString(), index)
                        db.imageAnnotationDao().shiftPageIndicesDown(uri.toString(), index)
                        db.textAnnotationDao().deleteForPage(uri.toString(), index)
                        db.textAnnotationDao().shiftPageIndicesDown(uri.toString(), index)
                    }
                    pdfViewModel.deletePages(uri.toString(), listOf(index))
                },'''

new_tablet_call = '''                onDeletePages = { indices ->
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        // 必須從大到小刪除，才不會影響前面頁面的 index
                        val sortedIndices = indices.sortedDescending()
                        sortedIndices.forEach { index ->
                            db.strokeDao().clearPage(uri.toString(), index)
                            db.strokeDao().shiftPageIndicesDown(uri.toString(), index)
                            db.imageAnnotationDao().deleteForPage(uri.toString(), index)
                            db.imageAnnotationDao().shiftPageIndicesDown(uri.toString(), index)
                            db.textAnnotationDao().deleteForPage(uri.toString(), index)
                            db.textAnnotationDao().shiftPageIndicesDown(uri.toString(), index)
                        }
                    }
                    pdfViewModel.deletePages(uri.toString(), indices)
                },'''

old_sidebar_sig = '''@Composable
private fun Sidebar(
    sidebarMode: SidebarMode,
    onModeChange: (SidebarMode) -> Unit,
    pdfViewModel: PdfViewModel,
    pageCount: Int,
    currentPageIndex: Int,
    db: AppDatabase,
    documentUri: String,
    onPageSelected: (Int) -> Unit,
    onAddPage: (afterIndex: Int) -> Unit,
    onDeletePage: (Int) -> Unit,'''

new_sidebar_sig = '''@Composable
private fun Sidebar(
    sidebarMode: SidebarMode,
    onModeChange: (SidebarMode) -> Unit,
    pdfViewModel: PdfViewModel,
    pageCount: Int,
    currentPageIndex: Int,
    db: AppDatabase,
    documentUri: String,
    onPageSelected: (Int) -> Unit,
    onAddPage: (afterIndex: Int) -> Unit,
    onDeletePages: (List<Int>) -> Unit,'''

content = content.replace(old_tablet_call, new_tablet_call)
content = content.replace(old_sidebar_sig, new_sidebar_sig)

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print("Sig replacement done.")
