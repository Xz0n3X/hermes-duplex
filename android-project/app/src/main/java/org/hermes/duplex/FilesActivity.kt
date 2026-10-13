package org.hermes.duplex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.hermes.duplex.ui.FilesModel
import org.hermes.duplex.ui.FilesScreen
import org.hermes.duplex.ui.theme.HermesTheme

/**
 * A file manager inside the app: the phone's own workspace, any folder you
 * connect (Google Drive, a USB drive, an SD card) and GitHub repositories you
 * can pull into the workspace and push back from it.
 */
class FilesActivity : ComponentActivity() {
    private lateinit var model: FilesModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = FilesModel(this)
        setContent { HermesTheme { FilesScreen(model) { finish() } } }
    }
}
