package com.vic.inkflow.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {

    // The schemes live in `:shared` so the desktop runs the same ones. It used to keep
    // its own copy, which had drifted into a different hue family entirely (blue navy
    // `Night0..3` instead of the tablet's near-neutral charcoal). See the note on
    // `InkDarkScheme`.
    val colorScheme = if (darkTheme) InkDarkScheme else InkLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // 全螢幕：藏頂部狀態列＋底部導航三鍵（時間/電量/返回/桌面），下滑/邊緣上滑可短暫叫回
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = ShapeSm,
            small = ShapeSm,
            medium = ShapeMd,
            large = ShapeLg,
            extraLarge = ShapeXl
        ),
        content = content
    )
}
