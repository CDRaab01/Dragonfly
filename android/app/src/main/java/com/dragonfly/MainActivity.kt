package com.dragonfly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dragonfly.ui.DragonflyNavGraph
import com.dragonfly.ui.theme.DragonflyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // The static launcher shortcut (if any) that opened us. Updated on a warm re-launch too
    // (launchMode=singleTask delivers those via onNewIntent), so the nav can react.
    private var shortcutTarget by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shortcutTarget = intent?.shortcutTarget()
        setContent {
            DragonflyTheme {
                DragonflyNavGraph(
                    shortcutTarget = shortcutTarget,
                    onShortcutHandled = { shortcutTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shortcutTarget = intent.shortcutTarget()
    }
}

/** The `dragonfly://shortcut/<target>` segment for a launcher-shortcut VIEW intent, else null. */
private fun Intent.shortcutTarget(): String? =
    if (action == Intent.ACTION_VIEW && data?.scheme == "dragonfly") data?.lastPathSegment else null
