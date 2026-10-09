package com.mtgscanbuild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.mtgscanbuild.ui.HomeScreen
import com.mtgscanbuild.ui.DeckDetailScreen
import com.mtgscanbuild.ui.DecksScreen
import com.mtgscanbuild.ui.MoxfieldScreen
import com.mtgscanbuild.ui.ScanScreen
import com.mtgscanbuild.ui.SettingsScreen
import com.mtgscanbuild.ui.ProScreen
import com.mtgscanbuild.ui.ProGate
import com.mtgscanbuild.ui.ManaWallpaper
import com.mtgscanbuild.ui.AdvertisingProvider
import com.mtgscanbuild.ui.BasicBanner
import com.mtgscanbuild.ui.access
import com.mtgscanbuild.ui.settings
import com.mtgscanbuild.data.ProFeature

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as MtgApp).entitlements.refreshOnResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AdvertisingProvider(application.access.adFree) { AppRoot() } } }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Suppress("DEPRECATION")
private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("scan", "Scan", Icons.Filled.Search),
    Tab("collection", "Collection", Icons.Filled.List),
    Tab("decks", "Decks", Icons.Filled.Build),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as android.app.Application
    val access = app.access
    val start = remember { app.settings.startPage.route }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            Column(if (tabs.any { it.route == current }) Modifier else Modifier.navigationBarsPadding()) {
                BasicBanner(current, access.adFree)
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
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (current != "scan") ManaWallpaper(Modifier.matchParentSize())
            NavHost(nav, startDestination = start) {
                composable("home") { HomeScreen() }
                composable("scan") { ScanScreen() }
                composable("settings") { SettingsScreen(onPro = { nav.navigate("pro") }) }
                composable("pro") { ProScreen(onBack = { nav.popBackStack() }) }
                composable("collection") { CollectionScreen(onOpen = { nav.navigate("card/$it") }, onPro = { nav.navigate("pro") }) }
                composable("card/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    CardDetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                }
                composable("decks") {
                    DecksScreen(onBuild = { nav.navigate("builder") }, onOpen = { nav.navigate("deck/$it") },
                        onMoxfield = { nav.navigate("moxfield") })
                }
                composable("builder") {
                    if (!access.hasPro) ProGate(ProFeature.DECK_GENERATION) { nav.navigate("pro") }
                    else BuilderScreen(onBack = { nav.popBackStack() }, onSaved = { id ->
                        nav.navigate("deck/$id") { popUpTo("decks") }
                    }, onMoxfield = { nav.navigate("moxfield") })
                }
                composable("moxfield") {
                    if (!access.hasPro) ProGate(ProFeature.MOXFIELD) { nav.navigate("pro") }
                    else MoxfieldScreen(onBack = { nav.popBackStack() }, onSaved = { id ->
                        nav.navigate("deck/$id") { popUpTo("decks") }
                    })
                }
                composable("deck/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    DeckDetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
