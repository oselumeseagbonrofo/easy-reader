package com.ebookreader.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ebookreader.app.ui.library.LibraryScreen
import com.ebookreader.app.ui.library.LibraryViewModel
import com.ebookreader.app.ui.reader.ReaderScreen
import com.ebookreader.app.ui.reader.ReaderViewModel
import com.ebookreader.app.ui.theme.EasyReaderTheme

class MainActivity : ComponentActivity() {

    private var pendingExternalUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            extractIncomingViewUri(intent)?.let { pendingExternalUri = it }
        }

        val appContainer = (application as EbookReaderApp).container

        setContent {
            EasyReaderTheme {
                val navController = rememberNavController()
                val libraryViewModel: LibraryViewModel = viewModel(
                    factory = LibraryViewModel.factory(appContainer.libraryRepository)
                )

                val incomingUri = pendingExternalUri
                LaunchedEffect(incomingUri) {
                    if (incomingUri != null) {
                        pendingExternalUri = null
                        libraryViewModel.importExternalBook(incomingUri, openImmediately = true)
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "library"
                ) {
                    composable("library") {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenBook = { bookId ->
                                navController.navigate("reader/$bookId") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(
                        route = "reader/{bookId}",
                        arguments = listOf(
                            navArgument("bookId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                        val context = LocalContext.current
                        val readerViewModel: ReaderViewModel = viewModel(
                            key = "reader_$bookId",
                            factory = ReaderViewModel.factory(
                                context = context,
                                bookId = bookId,
                                libraryRepository = appContainer.libraryRepository,
                                preferencesRepository = appContainer.readerPreferencesRepository
                            )
                        )
                        ReaderScreen(
                            viewModel = readerViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractIncomingViewUri(intent)?.let { pendingExternalUri = it }
    }

    private fun extractIncomingViewUri(intent: Intent?): Uri? {
        if (intent?.action == Intent.ACTION_VIEW) {
            return intent.data
        }
        return null
    }
}
