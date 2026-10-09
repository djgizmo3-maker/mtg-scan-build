package com.mtgscanbuild

import com.mtgscanbuild.data.Accent
import com.mtgscanbuild.data.ManaLand
import com.mtgscanbuild.ui.MANA_WALLPAPER_OPACITY
import org.junit.Assert.assertEquals
import org.junit.Test

class ManaThemeTest {
    @Test
    fun manaThemesCoverEveryLandAndKeepSavedPreferenceIdentifiers() {
        assertEquals(
            listOf("ARCANE", "ISLAND", "FOREST", "MOUNTAIN", "PLAINS", "SWAMP", "DYNAMIC"),
            Accent.entries.map { it.name },
        )
        assertEquals(ManaLand.entries.toSet(), Accent.entries.map { it.land }.toSet())
        assertEquals(ManaLand.WASTES, Accent.ARCANE.land)
        assertEquals(ManaLand.WASTES, Accent.DYNAMIC.land)
        assertEquals(ManaLand.PLAINS, Accent.PLAINS.land)
        assertEquals(ManaLand.ISLAND, Accent.ISLAND.land)
        assertEquals(ManaLand.SWAMP, Accent.SWAMP.land)
        assertEquals(ManaLand.MOUNTAIN, Accent.MOUNTAIN.land)
        assertEquals(ManaLand.FOREST, Accent.FOREST.land)
    }

    @Test
    fun wallpaperUsesEighteenPercentOpacity() {
        assertEquals(0.18f, MANA_WALLPAPER_OPACITY, 0.0001f)
    }
}
