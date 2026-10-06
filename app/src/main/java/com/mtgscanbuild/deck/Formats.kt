package com.mtgscanbuild.deck

enum class CommanderRule { NONE, COMMANDER, BRAWL, OATHBREAKER, PAUPER_COMMANDER }

data class Format(
    val id: String,
    val name: String,
    val deckSize: Int,
    val singleton: Boolean,
    val commander: CommanderRule,
    val note: String,
) {
    val hasCommander get() = commander != CommanderRule.NONE
}

object Formats {
    private val N = CommanderRule.NONE
    val all = listOf(
        Format("standard", "Standard", 60, false, N, "60 cards, 4-of, recent sets"),
        Format("pioneer", "Pioneer", 60, false, N, "60 cards, Return to Ravnica onward"),
        Format("modern", "Modern", 60, false, N, "60 cards, 8th Edition onward"),
        Format("legacy", "Legacy", 60, false, N, "60 cards, all sets, banned list"),
        Format("vintage", "Vintage", 60, false, N, "60 cards, restricted cards limited to 1"),
        Format("pauper", "Pauper", 60, false, N, "60 cards, commons only"),
        Format("commander", "Commander (EDH)", 100, true, CommanderRule.COMMANDER, "100-card singleton with a legendary commander"),
        Format("duel", "Duel Commander", 100, true, CommanderRule.COMMANDER, "1v1 Commander banlist"),
        Format("paupercommander", "Pauper Commander (PDH)", 100, true, CommanderRule.PAUPER_COMMANDER, "Uncommon creature commander, 99 commons"),
        Format("predh", "PreDH", 100, true, CommanderRule.COMMANDER, "Commander with pre-2020 cards"),
        Format("oathbreaker", "Oathbreaker", 60, true, CommanderRule.OATHBREAKER, "60-card singleton, planeswalker + signature spell"),
        Format("brawl", "Brawl", 100, true, CommanderRule.BRAWL, "100-card singleton, Arena card pool"),
        Format("standardbrawl", "Standard Brawl", 60, true, CommanderRule.BRAWL, "60-card singleton, Standard card pool"),
        Format("historic", "Historic (Arena)", 60, false, N, "Arena eternal format"),
        Format("timeless", "Timeless (Arena)", 60, false, N, "Arena format with every Arena card"),
        Format("alchemy", "Alchemy (Arena)", 60, false, N, "Arena digital Standard"),
        Format("gladiator", "Gladiator (Arena)", 100, true, N, "100-card singleton, no commander"),
        Format("penny", "Penny Dreadful", 60, false, N, "Cheapest cards on MTGO"),
        Format("premodern", "Premodern", 60, false, N, "4th Edition to Scourge"),
        Format("oldschool", "Old School 93/94", 60, false, N, "Alpha to Fallen Empires"),
        Format("future", "Future Standard", 60, false, N, "Standard including announced sets"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}
