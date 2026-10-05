package com.vic.inkflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.filled.List as FilledListIcon
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeSm
import dev.chrisbanes.haze.HazeState
@Composable
fun LibraryHeroPanel(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isDarkTheme: Boolean,
    isGridView: Boolean,
    onToggleGridView: () -> Unit,
    selectedNavIndex: Int = 0,
    onCreateFolder: () -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Surface(
            modifier = Modifier.glassPanel(hazeState, isDarkTheme),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 文青字標：兩字都襯線斜體輕字，錯峰進場 + InkFlow 流光
                var wordmarkVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { wordmarkVisible = true }
                val inkAlpha by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 1f else 0f,
                    animationSpec = tween(550),
                    label = "WordInkAlpha"
                )
                val inkSlide by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 0f else -26f,
                    animationSpec = tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "WordInkSlide"
                )
                val studioAlpha by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 1f else 0f,
                    animationSpec = tween(550, delayMillis = 200),
                    label = "WordStudioAlpha"
                )
                val studioSlide by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 0f else -18f,
                    animationSpec = tween(550, delayMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "WordStudioSlide"
                )
                // 靜模式：shimmer 凍結（不跑無限動畫）
                val quiet = LocalQuietMode.current
                val shimmerX = if (quiet) 0.45f else rememberInfiniteTransition(label = "WordShimmer")
                    .animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 2800, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "WordShimmerX"
                    ).value
                var inkWidth by remember { mutableFloatStateOf(0f) }
                val sweep = inkWidth.coerceAtLeast(1f) * (shimmerX * 1.8f - 0.4f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "InkFlow",
                        modifier = Modifier.graphicsLayer {
                            alpha = inkAlpha
                            translationX = inkSlide
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = (-0.5).sp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color.White.copy(alpha = 0.9f),
                                    MaterialTheme.colorScheme.primary
                                ),
                                start = Offset(sweep, 0f),
                                end = Offset(sweep + inkWidth.coerceAtLeast(1f) * 0.4f, 0f)
                            )
                        ),
                        onTextLayout = { inkWidth = it.size.width.toFloat() },
                        maxLines = 1
                    )
                    Text(
                        text = "  Studio",
                        modifier = Modifier.graphicsLayer {
                            alpha = studioAlpha
                            translationX = studioSlide
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 內嵌搜尋丸：半透明凹槽 + 無框，嵌在玻璃面板裡自成一層
                    val searchVeil = Color.White.copy(alpha = if (isDarkTheme) 0.10f else 0.55f)
                    val searchVeilFocused = Color.White.copy(alpha = if (isDarkTheme) 0.16f else 0.70f)
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            AnimatedVisibility(visible = searchQuery.isNotBlank()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除搜尋")
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                "搜尋筆記標題或文件名稱…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = searchVeilFocused,
                            unfocusedContainerColor = searchVeil,
                            disabledContainerColor = searchVeil,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = CircleShape
                    )
                    
                    if (selectedNavIndex == 1) {
                        // 玻璃丸 + primary 字：跟 view toggle 同語言
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fauxGlassPanel(isDarkTheme, CircleShape)
                                .glassClickable(onClick = onCreateFolder, shape = CircleShape)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("新增資料夾", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    if (selectedNavIndex == 0) {
                        // 玻璃幽靈鈕：跟面板同一塊玻璃，不再用實心圓底
                        Box(
                            modifier = Modifier.glassPanel(hazeState, isDarkTheme, CircleShape)
                        ) {
                            IconButton(onClick = onToggleGridView) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.FilledListIcon else Icons.Outlined.GridView,
                                    contentDescription = "切換檢視",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
