package com.example.eyesai.ui.components

import android.net.Uri
import android.util.Log
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.eyesai.AppScreen
import com.example.eyesai.NavigationScreen
import com.example.eyesai.NotesScreen
import com.example.eyesai.ShoppingScreen
import com.example.eyesai.ui.screen.CameraPreviewScreen
import com.example.eyesai.ui.screen.HomeScreen

@Composable
fun Navigator(
    navController: NavHostController = rememberNavController(),
    onImageCaptured: (Uri) -> Unit,
    isVoiceCommandActive: Boolean = false
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = AppScreen.valueOf(
        backStackEntry?.destination?.route ?: AppScreen.Home.name
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppBar(
                currentScreen = currentScreen,
                onNavigateBack = { navController.navigateUp() }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Home,
                    onClick = { navController.navigate(AppScreen.Home.name) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text(text = stringResource(AppScreen.Home.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Navigation,
                    onClick = { navController.navigate(AppScreen.Navigation.name) },
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = "Navigation") },
                    label = { Text(text = stringResource(AppScreen.Navigation.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Shop,
                    onClick = { navController.navigate(AppScreen.Shop.name) },
                    icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = "Shop") },
                    label = { Text(text = stringResource(AppScreen.Shop.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Describe,
                    onClick = { navController.navigate(AppScreen.Describe.name) },
                    icon = { Icon(Icons.Default.Face, contentDescription = "Describe") },
                    label = { Text(text = stringResource(AppScreen.Describe.title)) }
                )
                NavigationBarItem(
                    selected = currentScreen == AppScreen.Notes,
                    onClick = { navController.navigate(AppScreen.Notes.name) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Notes") },
                    label = { Text(text = stringResource(AppScreen.Notes.title)) }
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = AppScreen.Home.name,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(
                AppScreen.Home.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                HomeScreen(navController)
            }
            composable(
                AppScreen.Shop.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                ShoppingScreen(navController)
            }
            composable(
                AppScreen.Describe.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                CameraPreviewScreen(onImageCaptured = onImageCaptured , onError = { Log.e("Camerax", "Error capturing image") }, isVoiceCommandActive)
            }
            composable(
                AppScreen.Navigation.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                NavigationScreen(navController)
            }
            composable(
                AppScreen.Notes.name,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                NotesScreen(navController)
            }
        }
    }
}
