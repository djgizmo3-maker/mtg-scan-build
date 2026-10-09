package com.mtgscanbuild.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.mtgscanbuild.scan.ScanSound
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class ThemeMode(val label: String) { SYSTEM("Follow phone"), DARK("Dark"), LIGHT("Light") }

enum class AdAudience(val label: String) {
    UNSET("Not selected"), UNDER_13("Under 13"), TEEN("13-17"), ADULT("18 or older"),
}

enum class ManaLand { PLAINS, ISLAND, SWAMP, MOUNTAIN, FOREST, WASTES }

enum class Accent(val label: String, val dark: Long, val light: Long, val land: ManaLand) {
    ARCANE("Colorless - Wastes", 0xFFC0BEC9, 0xFF625F72, ManaLand.WASTES),
    ISLAND("Blue mana - Island", 0xFF6EB3E8, 0xFF1F5F99, ManaLand.ISLAND),
    FOREST("Green mana - Forest", 0xFF7BC896, 0xFF2E7D4F, ManaLand.FOREST),
    MOUNTAIN("Red mana - Mountain", 0xFFEF8A72, 0xFFB23A26, ManaLand.MOUNTAIN),
    PLAINS("White mana - Plains", 0xFFF2CF6B, 0xFF8A6A00, ManaLand.PLAINS),
    SWAMP("Black mana - Swamp", 0xFFB8ADB4, 0xFF5B4E57, ManaLand.SWAMP),
    DYNAMIC("Phone colors - Wastes (Android 12+)", 0xFFC0BEC9, 0xFF625F72, ManaLand.WASTES),
}

/** How many consecutive camera frames must agree before a card counts as recognized. */
enum class ScanSensitivity(val label: String, val confidentFrames: Int, val fuzzyFrames: Int) {
    FAST("Fast (may misread more)", 1, 2),
    BALANCED("Balanced", 2, 3),
    STRICT("Strict (fewest mistakes)", 3, 4),
}

/** User preferences, persisted in SharedPreferences and observable from Compose. */
class AppSettings(context: Context, private val access: PlanAccess = PlanAccess()) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Scanning
    var autoAdd by bool("auto_add", false)
    var vibrate by bool("vibrate", true)
    var keepScreenOn by bool("keep_screen_on", true)
    var startWithLight by bool("start_with_light", false)
    var sensitivity by enumPref("sensitivity", ScanSensitivity.BALANCED)

    // Scan sound
    var soundOn by bool("sound_on", true)
    var sound by enumPref("sound", ScanSound.CHIME)
    var soundVolume by float("sound_volume", 0.8f)
    var customSoundUri by string("custom_sound_uri")
    var customSoundName by string("custom_sound_name")

    // Appearance
    var themeMode by enumPref("theme", ThemeMode.DARK)
    var accent by enumPref("accent", Accent.ARCANE)
    var showImages by bool("show_images", true)
    var adAudience by enumPref("ad_audience", AdAudience.UNSET)
    private var savedStartPage by enumPref("start_page", StartPage.HOME)
    var startPage: StartPage
        get() = access.openingPage(savedStartPage)
        set(value) {
            access.requirePro(ProFeature.START_PAGE)
            savedStartPage = value
        }

    // Deck building
    var defaultFormat by string("default_format", "commander")
    var assumeBasics by bool("assume_basics", true)

    // Prices (TCGplayer market price via Scryfall)
    var autoRefreshPrices by bool("auto_refresh_prices", true)
    var lastPriceRefresh by long("last_price_refresh", 0L)

    private fun bool(key: String, def: Boolean) = pref(prefs.getBoolean(key, def)) { putBoolean(key, it) }
    private fun long(key: String, def: Long) = pref(prefs.getLong(key, def)) { putLong(key, it) }
    private fun float(key: String, def: Float) = pref(prefs.getFloat(key, def)) { putFloat(key, it) }
    private fun string(key: String) = pref<String?>(prefs.getString(key, null)) { putString(key, it) }
    private fun string(key: String, def: String) = pref(prefs.getString(key, def) ?: def) { putString(key, it) }
    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E) =
        pref(prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def) { putString(key, it.name) }

    private fun <T> pref(initial: T, write: android.content.SharedPreferences.Editor.(T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            private val state = mutableStateOf(initial)
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state.value = value
                prefs.edit().apply { write(value) }.apply()
            }
        }
}
