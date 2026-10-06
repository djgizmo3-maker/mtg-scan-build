package com.mtgscanbuild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mtgscanbuild.ui.AppTheme
import com.mtgscanbuild.ui.BuilderScreen
import com.mtgscanbuild.ui.CardDetailScreen
import com.mtgscanbuild.ui.CollectionScreen
import com.mtgscanbuild.ui.DeckDetailScreen
import com.mtgscanbuild.ui.DecksScreen
import com.mtgscanbuild.ui.ScanScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Suppress("DEPRECATION")
private val tabs = listOf(
    Tab("scan", "Scan", Icons.Filled.Search),
    Tab("collection", "Collection", Icons.Filled.List),
    Tab("decks", "Decks", Icons.Filled.Build),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == current }) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "scan", modifier = Modifier.padding(pad)) {
            composable("scan") { ScanScreen() }
            composable("collection") { CollectionScreen(onOpen = { nav.navigate("card/$it") }) }
            composable("card/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                CardDetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
            composable("decks") {
                DecksScreen(onBuild = { nav.navigate("builder") }, onOpen = { nav.navigate("deck/$it") })
            }
            composable("builder") {
                BuilderScreen(onBack = { nav.popBackStack() }, onSaved = { id ->
                    nav.navigate("deck/$id") { popUpTo("decks") }
                })
            }
            composable("deck/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DeckDetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
        }
    }
}
