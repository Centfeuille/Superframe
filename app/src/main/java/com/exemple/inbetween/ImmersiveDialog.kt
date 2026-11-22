package com.example.inbetween

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/*
 * A dialog is its own window, and it does not inherit the activity's hidden system bars: opening one brought the status and navigation bars back until it closed.
 * [AlertDialog] below has the same parameters as the Material 3 one and, because it lives in this package, it is the one every file here gets (an explicit
 * import of the Material one would win, so no file imports it): it hides the bars in the dialog's window as well.
 */

/** Hides the system bars in the window this composable is shown in, when that window is a dialog (the activity does it for itself in MainActivity). */
@Composable
fun HideSystemBarsInWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { HideSystemBarsInWindow(); confirmButton() }, // emits no layout: it only runs inside the dialog's window
        modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text, shape = shape, containerColor = containerColor,
        iconContentColor = iconContentColor, titleContentColor = titleContentColor, textContentColor = textContentColor, tonalElevation = tonalElevation, properties = properties
    )
}
