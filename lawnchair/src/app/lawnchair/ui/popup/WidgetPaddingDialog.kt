package app.lawnchair.ui.popup

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.data.widget.WidgetPaddingRepository
import app.lawnchair.launcher
import app.lawnchair.util.navigationBarsOrDisplayCutoutPadding
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.R

private const val MIN_SCALE = 50f
private const val MAX_SCALE = 200f

@Composable
fun WidgetPaddingDialog(
    appWidgetId: Int,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = context.launcher
    val repository = remember { WidgetPaddingRepository.INSTANCE.get(context) }

    // Size is a scale percentage: 100 = natural cell size, >100 enlarges past the cell.
    var scale by remember { mutableFloatStateOf(repository.getScalePercent(appWidgetId).toFloat()) }
    var saved by remember { mutableStateOf(false) }

    fun preview(scalePercent: Int) {
        launcher.workspace?.getWidgetForAppWidgetId(appWidgetId)?.let { view ->
            WidgetPaddingRepository.applyScalePercent(view, scalePercent)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Revert the live preview to the persisted value if the user dismissed without saving.
            if (!saved) {
                launcher.workspace?.getWidgetForAppWidgetId(appWidgetId)?.let { view ->
                    WidgetPaddingRepository.applyStoredScale(view, appWidgetId)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .navigationBarsOrDisplayCutoutPadding()
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(id = R.string.widget_padding_label),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.widget_padding_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${scale.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Slider(
            value = scale,
            onValueChange = {
                scale = it
                preview(it.toInt())
            },
            valueRange = MIN_SCALE..MAX_SCALE,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClose) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
            TextButton(
                onClick = {
                    saved = true
                    repository.saveScale(appWidgetId, scale.toInt())
                    onClose()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text = stringResource(id = R.string.action_apply))
            }
        }
    }
}

/** Entry point callable from Java (e.g. the widget resize frame) to open the padding sheet. */
object WidgetPaddingSheet {
    @JvmStatic
    fun show(context: Context, appWidgetId: Int) {
        val launcher = context.launcher
        AbstractFloatingView.closeAllOpenViews(launcher)
        ComposeBottomSheet.show(
            context = launcher,
            contentPaddings = PaddingValues(bottom = 64.dp),
        ) {
            WidgetPaddingDialog(appWidgetId = appWidgetId) { close(true) }
        }
    }
}
