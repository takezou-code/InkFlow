package com.vic.inkflow.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.InkThemeState
import com.vic.inkflow.ui.chromeGlass
import com.vic.inkflow.ui.glassClickable
import com.vic.inkflow.ui.glassContentColor
import com.vic.inkflow.ui.glassSidePanel
import com.vic.inkflow.ui.theme.ShapeMd

/**
 * The desktop's design system.
 *
 * ## Why this file exists
 *
 * The Windows app grew screen by screen, so each screen invented its own padding
 * (6/8/10/12/14/16/24/28dp), its own header (or none), and its own idea of what a
 * button is. The result reads as several apps sharing a window: the library has a
 * hero panel and a search pill, settings has a title and a card, the reader has a
 * toolbar. None of them line up.
 *
 * The fix is structural, not cosmetic: **one scaffold, one spacing scale, one set
 * of controls**, and screens compose them instead of raw Material. That is also
 * what makes the AI panel a plug-in rather than a special case — it is a
 * [DeskSidePanel] like any other side panel, with content, not with chrome.
 *
 * ## Rules
 *
 *  - Spacing only from [DeskSpace] (the 8pt grid from `docs/specs/ui.md`).
 *  - Sizes only from [DeskDims], so a header is the same height on every screen.
 *  - No raw Material widgets on screen: `Button`, `OutlinedTextField`, `Card`,
 *    `TopAppBar`, `FilterChip` are banned by `ui.md`. The controls here are built
 *    on the shared glass kit, which is the one material the product has.
 */

/** The 8pt spacing scale. Nothing in a screen should need a number that is not here. */
object DeskSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Structural sizes. One header height, one rail width, one panel width. */
object DeskDims {
    /** The floating navigation rail. */
    val railWidth = 76.dp

    /** Height of a screen header row (title + actions). */
    val headerHeight = 64.dp

    /** Horizontal padding of a screen. The rail already provides the outer inset. */
    val contentPadding = 24.dp

    /** Widest the content column gets; beyond this, cards would stretch into banners. */
    val contentMaxWidth = 1400.dp

    /** Width of a side panel (AI, notes, future inspectors). */
    val sidePanelWidth = 380.dp

    /** Height of a button, a search field, an icon button. */
    val controlHeight = 40.dp

    /** A document card: page-shaped thumbnail plus two lines of text. */
    val cardMinWidth = 208.dp
    val cardHeight = 244.dp
}

/**
 * The skeleton every screen uses.
 *
 * A screen is a title, an optional one-line subtitle, actions on the right, and a
 * content area that fills the rest. The point is that switching screens does not
 * move the title, the buttons, or the padding — the window's furniture stays put
 * and only the content changes.
 *
 * @param content fills the remaining height. Screens that scroll put their own
 *   scroll container here (a grid, a column); the scaffold deliberately does not
 *   scroll, because a `LazyVerticalGrid` inside a scrolling parent has no height
 *   to measure.
 */
@Composable
fun DeskScreen(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = DeskDims.contentPadding,
                end = DeskDims.contentPadding,
                top = DeskSpace.md
            )
    ) {
        val actionScroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DeskDims.headerHeight)
        ) {
            // 標題有寬度上限（它本來就只顯示一行），剩下的全給動作列。
            // 舊制是標題 weight(1f)、動作列自然寬度：動作列一旦比視窗寬，
            // 就整個溢出到視窗外——「開啟 PDF」整顆消失，而且沒有任何捲動可達。
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(actionScroll),
                    content = actions
                )
                // 還有更多動作時，右邊放一顆**看得見的**箭頭讓人點。
                // 用漸層淡出是不行的：深色主題的漸層色跟背景幾乎一樣，
                // 看不出「被切一半」是故意的。箭頭沒有這個問題。
                if (actionScroll.maxValue > actionScroll.value) {
                    DeskIconButton(
                        icon = Icons.Outlined.ChevronRight,
                        description = "更多動作",
                        onClick = {
                            scope.launch {
                                actionScroll.animateScrollBy(160f)
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
        Spacer(Modifier.height(DeskSpace.md))
        Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

/** A hairline. A `Box`, not `HorizontalDivider`: M3 chrome is banned on screen. */
@Composable
fun DeskDivider(modifier: Modifier = Modifier, alpha: Float = 0.10f) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

/**
 * A round glass icon button.
 *
 * @param selected draws the glass pill behind the icon (the rail's selected state).
 */
@Composable
fun DeskIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val isDark = InkThemeState.darkMode
    Box(
        modifier = modifier
            .size(DeskDims.controlHeight)
            .then(if (selected) Modifier.chromeGlass(isDark, CircleShape) else Modifier)
            .clip(CircleShape)
            .glassClickable(onClick = onClick, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint ?: if (selected) MaterialTheme.colorScheme.primary else glassContentColor(isDark),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A filled primary action: the one button per screen that moves the work forward.
 *
 * Glass everywhere else, but the primary action carries the accent colour so the
 * user can find it without reading. It stays a pill (same shape as the other
 * controls) so it lines up with them; only the fill changes.
 */
@Composable
fun DeskPrimaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = InkThemeState.darkMode
    val fill = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .height(DeskDims.controlHeight)
            .clip(CircleShape)
            .background(fill)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled)
            .padding(horizontal = DeskSpace.md),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(DeskSpace.xs))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/** A glass pill with an icon and a label — the one shape a primary action takes. */
@Composable
fun DeskPillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = InkThemeState.darkMode
    val content = glassContentColor(isDark).copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        modifier = modifier
            .height(DeskDims.controlHeight)
            .chromeGlass(isDark, CircleShape)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled)
            .padding(horizontal = DeskSpace.md),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(DeskSpace.xs))
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * The search field.
 *
 * `BasicTextField` rather than M3's `OutlinedTextField`: the outline, the floating
 * label and the underline are Material's, not the product's, and a search box is
 * the one control every screen has — so it is the one place a second visual
 * language is most visible.
 */
@Composable
fun DeskSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val isDark = InkThemeState.darkMode
    // 鍵盤 focus ring：`BasicTextField` 沒有 M3 的 focus indicator，
    // 沒有這圈的話，用 Tab 走過畫面的人看不出焦點在哪一個欄位。
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(DeskDims.controlHeight)
            .widthIn(min = 200.dp)
            .chromeGlass(isDark, CircleShape)
            .then(
                if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .padding(horizontal = DeskSpace.sm)
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(DeskSpace.xs))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = glassContentColor(isDark)),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
    }
}

/**
 * A text field.
 *
 * `BasicTextField` inside a glass box, for the same reason as [DeskSearchField]:
 * M3's `OutlinedTextField` brings its own outline, floating label and 56dp
 * metrics, none of which belong to this product. Callers get a field that lines
 * up with the 40dp controls around it, and [singleLine] = false gives the
 * multi-line variant (the AI instruction box) with the same skin.
 */
@Composable
fun DeskTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4
) {
    val isDark = InkThemeState.darkMode
    val shape = if (singleLine) CircleShape else ShapeMd
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = DeskDims.controlHeight)
            .chromeGlass(isDark, shape)
            .then(
                if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .padding(
                horizontal = if (singleLine) DeskSpace.md else DeskSpace.sm,
                vertical = if (singleLine) DeskSpace.xs else DeskSpace.sm
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = glassContentColor(isDark)),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
    }
}

/**
 * A full-height side panel: header, divider, content, optional footer.
 *
 * This is the **plug point** for anything that accompanies the page — today the AI
 * note panel, later an outline or a comments rail. It owns the chrome (width,
 * glass, rounded leading corners, close button); the caller owns only the content.
 * That split is why adding a panel does not mean copying the AI panel's layout.
 *
 * @param content gets the remaining height and is **not** scrolled by the panel:
 *   a `LazyColumn` inside a scrolling parent has infinite height and crashes.
 *   Scroll in the content when the content needs it.
 */
@Composable
fun DeskSidePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleIcon: ImageVector? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    footer: @Composable (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = InkThemeState.darkMode
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(DeskDims.sidePanelWidth)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .glassSidePanel(isDark)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DeskSpace.md, end = DeskSpace.xxs, top = DeskSpace.sm, bottom = DeskSpace.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleIcon != null) {
                        Icon(
                            titleIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(DeskSpace.xs))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = glassContentColor(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            headerActions()
            DeskIconButton(
                icon = Icons.Outlined.Close,
                description = "收合面板",
                onClick = onClose
            )
        }
        DeskDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            content = content
        )
        if (footer != null) {
            DeskDivider()
            Box(modifier = Modifier.fillMaxWidth().padding(DeskSpace.md)) {
                footer()
            }
        }
    }
}

/** A section label inside a screen (not a screen title). */
@Composable
fun DeskSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
