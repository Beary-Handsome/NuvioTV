package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Trakt-style 5-star rating picker with half-star precision (1-10).
 *
 * D-pad LEFT/RIGHT moves the selection in 0.5-star increments (rating
 * value changes by 1 per press). OK confirms. BACK dismisses without
 * changes. The "Clear rating" button below the stars removes any
 * existing rating.
 *
 * Mirrors trakt.tv's web UI: hover left of a star = half-star value
 * (odd ratings: 1/3/5/7/9), hover right of a star = full-star value
 * (even ratings: 2/4/6/8/10).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StarRatingDialog(
    title: String,
    currentRating: Int?,
    onDismiss: () -> Unit,
    onRated: (Int) -> Unit,
    onCleared: () -> Unit
) {
    // Working selection; defaults to current rating if any, else 8 (a
    // sensible "I liked it" starting point — beats starting at 0 which
    // would mean the user has to step right 8 times for a typical rating).
    var pending by remember { mutableIntStateOf(currentRating?.coerceIn(1, 10) ?: 8) }

    val starsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        starsFocusRequester.requestFocusAfterFrames(frames = 2)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.rating_dialog_subtitle),
        // Suppress first KEY_UP: the long-press / quick-press that opened
        // this dialog can land its UP here and instantly fire the Save
        // button below, defeating the point of the picker.
        suppressFirstKeyUp = true,
        width = 560.dp
    ) {
        // Star row + numeric label. MUST be focusable() — without it the
        // Row is just a layout node and focus falls through to the Save
        // button, so D-pad LEFT/RIGHT navigates between buttons instead
        // of adjusting the rating.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(starsFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            pending = (pending - 1).coerceAtLeast(1); true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            pending = (pending + 1).coerceAtMost(10); true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            onRated(pending); true
                        }
                        else -> false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 5 stars; each star represents 2 rating-points. Star N is:
            // - empty if pending < 2N-1
            // - half if pending == 2N-1
            // - full if pending >= 2N
            for (starIdx in 1..5) {
                val fullThreshold = starIdx * 2
                val halfThreshold = fullThreshold - 1
                val icon = when {
                    pending >= fullThreshold -> Icons.Filled.Star
                    pending >= halfThreshold -> Icons.Filled.StarHalf
                    else -> Icons.Outlined.StarOutline
                }
                val tint = if (pending >= halfThreshold) {
                    Color(0xFFFFC107) // amber — matches Trakt's web UI
                } else {
                    NuvioColors.TextTertiary
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "${pending}/10",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onRated(pending) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFFFFC107),
                contentColor = Color.Black
            )
        ) {
            Text(stringResource(R.string.rating_dialog_save))
        }

        if (currentRating != null) {
            Button(
                onClick = onCleared,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.rating_dialog_clear))
            }
        }
    }
}
