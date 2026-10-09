package com.mtgscanbuild.data

object HomeSources {
    const val NEWS = "https://magic.wizards.com/en/news"
    const val BANS = "https://magic.wizards.com/en/banned-restricted-list"
    const val RULES = "https://magic.wizards.com/en/rules"
    const val BASICS = "https://magic.wizards.com/en/how-to-play"
    const val TOURNAMENTS = "https://wpn.wizards.com/en/rules-documents"
}

enum class MatchType(val label: String) {
    CONSTRUCTED("Tabletop / 1v1"),
    MULTIPLAYER("Multiplayer"),
    ARENA("MTG Arena"),
    LIMITED("Limited"),
    TEAM("Team play"),
}

data class HomeMode(
    val id: String,
    val name: String,
    val matchType: MatchType,
    val rules: List<String>,
    val rulesUrl: String,
    val legalityId: String? = id,
)

object HomeModes {
    private fun constructed(id: String, name: String, pool: String) = HomeMode(
        id, name, MatchType.CONSTRUCTED,
        listOf("Start at 20 life. Build a deck of at least 60 cards.",
            "Normally up to four copies of a card across the deck and sideboard, except basic lands and cards with their own exceptions.",
            "A sideboard may contain up to 15 cards. Event rules determine whether matches are best-of-one or best-of-three.",
            pool),
        "https://magic.wizards.com/en/formats/$id"
    )

    private fun arena(id: String, name: String, pool: String) = HomeMode(
        id, name, MatchType.ARENA,
        listOf("Start at 20 life with a deck of at least 60 cards.",
            "Normally a four-copy limit, with basic lands and card-specific exceptions.",
            "Arena best-of-one and Traditional best-of-three use different sideboard access rules; check the current Arena guide.",
            pool), if (id == "alchemy") "https://magic.wizards.com/en/mtgarena/alchemy"
            else "https://magic.wizards.com/en/formats/$id"
    )

    val all = listOf(
        constructed("standard", "Standard", "Uses the current Standard card pool. Rotation changes eligibility; rotation is not a ban."),
        constructed("pioneer", "Pioneer", "Uses Pioneer-eligible sets beginning with Return to Ravnica. It does not rotate."),
        constructed("modern", "Modern", "Uses Modern-eligible sets beginning with Eighth Edition and Mirrodin, plus designated additions."),
        constructed("legacy", "Legacy", "Uses the Legacy card pool from Magic's history, subject to its banned list."),
        constructed("vintage", "Vintage", "Uses the Vintage card pool. Restricted cards are limited to one copy across deck and sideboard."),
        constructed("pauper", "Pauper", "Cards must have an eligible common printing in paper or Magic Online, and must not be banned."),
        HomeMode("commander", "Commander (EDH)", MatchType.MULTIPLAYER,
            listOf("Normally multiplayer, with 40 starting life.",
                "Build exactly 100 cards including your commander or commanders. Normally singleton except basic lands and card-specific exceptions.",
                "Every card must fit your commander's color identity.",
                "Casting a commander from the command zone costs two extra generic mana for each previous cast of that commander from there.",
                "Taking 21 combat damage from the same commander causes a player to lose.",
                "Agree on power level and house rules with the table; check current Commander guidance."),
            "https://magic.wizards.com/en/formats/commander"),
        HomeMode("oathbreaker", "Oathbreaker", MatchType.MULTIPLAYER,
            listOf("Start at 20 life, usually in multiplayer.",
                "Build exactly 60 cards: a planeswalker Oathbreaker, an instant or sorcery Signature Spell, and 58 other cards.",
                "Use singleton construction and your Oathbreaker's color identity, with basic-land and card-specific exceptions.",
                "Cast the Signature Spell from the command zone only while you control your Oathbreaker on the battlefield.",
                "Oathbreaker and Signature Spell each track their own additional command-zone casting cost."),
            "https://magic.wizards.com/en/formats/oathbreaker"),
        arena("alchemy", "Alchemy", "A rotating Arena format with digital-only and rebalanced cards; its card pool differs from Standard."),
        arena("historic", "Historic", "An Arena nonrotating format with its own bans and digital rebalancing."),
        arena("timeless", "Timeless", "Uses Arena's broad Timeless card pool. Restricted cards are limited to one copy across deck and sideboard."),
        HomeMode("brawl", "Brawl (Arena)", MatchType.ARENA,
            listOf("Arena Brawl is 1v1 with 25 starting life and exactly 100 cards including a commander.",
                "Use a legendary creature or planeswalker as commander, singleton construction, and its color identity.",
                "Basic lands and cards with explicit construction exceptions are exempt from the singleton limit.",
                "The command-zone casting surcharge increases by two generic mana for each previous cast from there."),
            "https://magic.wizards.com/en/formats/brawl"),
        HomeMode("standardbrawl", "Standard Brawl", MatchType.ARENA,
            listOf("Build exactly 60 cards including a legendary creature or planeswalker commander.",
                "Use the Standard card pool, singleton construction, and the commander's color identity.",
                "Start at 25 life for 1v1; multiplayer Brawl uses 30 life.",
                "Basic lands and explicit card exceptions can repeat. Command-zone casts have the increasing two-mana surcharge."),
            "https://magic.wizards.com/en/formats/brawl"),
        HomeMode("draft", "Booster Draft", MatchType.LIMITED,
            listOf("Draft cards from packs by choosing a card and passing the rest; a typical draft uses three packs.",
                "Build at least 40 cards from your drafted pool, adding basic lands as needed.",
                "Start at 20 life. Your remaining pool is your sideboard.",
                "The usual Constructed four-copy limit does not apply. Special draft products may have their own procedures."),
            "https://magic.wizards.com/en/formats/booster-draft", legalityId = null),
        HomeMode("sealed", "Sealed Deck", MatchType.LIMITED,
            listOf("Build from the packs provided by the event; typical Sealed events use six boosters.",
                "Use at least 40 cards, with basic lands added as needed.",
                "Start at 20 life. Cards left in your opened pool form the sideboard.",
                "The Constructed four-copy limit does not apply; check event-specific card-pool rules."),
            "https://magic.wizards.com/en/formats/sealed-deck", legalityId = null),
        HomeMode("twoheaded", "Two-Headed Giant", MatchType.TEAM,
            listOf("Two players form a team and take turns together, normally with a shared starting life total of 30.",
                "Hands, mana, and permanents belong to individual players rather than being pooled.",
                "Constructed decks have at least 60 cards; the team's combined decks normally share a four-copy limit.",
                "Limited teams build at least 40 cards per deck from a shared pool.",
                "Commander Two-Headed Giant has different life and deck-construction rules; consult the full variant rules."),
            "https://magic.wizards.com/en/formats/two-headed-giant", legalityId = null),
    )
    val withBanLists = all.filter { it.legalityId != null }
}

data class NewsArticle(val title: String, val url: String, val publishedDate: String?)
data class LegalityCard(val id: String, val name: String, val url: String, val status: String)
data class BanLists(val banned: List<LegalityCard>, val restricted: List<LegalityCard>)
