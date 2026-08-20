package com.faker1024.icloudsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.faker1024.icloudsync.core.web.CloudDriveSession
import com.faker1024.icloudsync.feature.main.MainScreen
import com.faker1024.icloudsync.feature.main.MainViewModel
import com.faker1024.icloudsync.ui.theme.ICloudSyncTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ICloudSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ICloudSyncApp()
                }
            }
        }
    }
}

@Composable
private fun ICloudSyncApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::enqueueImport)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        documentLauncher.launch(SUPPORTED_INPUT_TYPES)
    }

    MainScreen(
        viewModel = viewModel,
        onSelectFile = {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                documentLauncher.launch(SUPPORTED_INPUT_TYPES)
            }
        },
        onClearICloudSession = { CloudDriveSession.clear() },
    )
}

private val SUPPORTED_INPUT_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
    "image/*",
    "video/*",
)
