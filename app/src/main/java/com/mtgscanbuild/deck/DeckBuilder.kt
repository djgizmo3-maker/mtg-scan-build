package com.mtgscanbuild.deck

import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.frontName
import com.mtgscanbuild.data.frontType
import com.mtgscanbuild.data.legalityMap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One unique card (by name) from the collection, evaluated for one format. */
class PoolCard(val card: CardData, val owned: Int, val legality: String, val legalities: Map<String, String>) {
    val name = card.name
    val type = card.frontType
    val text = card.oracleText.lowercase()
    val isLand = type.contains("Land")
    val isBasic = isLand && type.contains("Basic")
    val isCreature = type.contains("Creature")
    val isLegendary = type.contains("Legendary")
    val identity: Set<Char> = card.colorIdentity.toSet()
    val cmc = card.cmc
    val subtypes: Set<String> = type.substringAfter("—", "").split(' ')
        .map { it.trim().lowercase() }.filter { it.length > 1 }.toSet()
    val keywords: Set<String> = card.keywords.lowercase().split('|').filter { it.isNotEmpty() }.toSet()
    val tags: Set<String> = Tags.of(text, type, keywords)
    val quality: Double = Scoring.quality(this)
    val bucket: Int get() = cmc.toInt().coerceIn(1, 6)
}

object Tags {
    private val rules = listOf(
        "removal" to Regex("(destroy|exile) (target|up to one target|another target) (creature|permanent|nonland|artifact|enchantment|planeswalker|attacking|tapped|nontoken)|deals? (\\d+|x) damage to (any target|target creature|target attacking|target player or planeswalker|target creature or planeswalker|any one target)|target creature (gets|an opponent controls gets) -\\d+/-\\d+|fights? (target|another target|up to one target)|return target (creature|nonland permanent) to its owner's hand|target (player|opponent) sacrifices"),
        "counter" to Regex("counter target (spell|noncreature|creature|instant|activated|artifact)"),
        "wipe" to Regex("(destroy|exile) all (creatures|nonland|other creatures|artifacts|enchantments|permanents|nontoken)|deals? (\\d+|x) damage to each (creature|other creature)|all creatures get -|each player sacrifices"),
        "draw" to Regex("draws? (a|two|three|four|x|that many) cards?|draw cards equal|investigate|connives?|look at the top \\w+ cards of your library\\. put (one|two|any number)"),
        "ramp" to Regex("search your library for (a|up to (one|two|three)) (basic )?(land|forest|plains|island|swamp|mountain)|put (a|up to one|up to two) land cards?|\\{t\\}: add \\{|add (one|two|three) mana|create (a|two|three) treasure"),
        "tokens" to Regex("create (a|an|one|two|three|four|five|x|that many|\\d+) [^.]*tokens?"),
        "counters" to Regex("\\+1/\\+1 counters?|proliferate"),
        "graveyard" to Regex("from (your|a) graveyard|\\bmills?\\b|flashback|escape"),
        "lifegain" to Regex("you gain (\\d+|x )?life|gains? (\\d+|x) life|whenever you gain life"),
        "sacrifice" to Regex("sacrifice (a|another|an) (creature|permanent|artifact)|whenever (a|another) creature (you control )?dies"),
        "artifacts" to Regex("artifacts? you control|artifact spell|equipped creature|whenever an artifact"),
        "enchantments" to Regex("enchantments? you control|enchantment spell|enchanted creature|constellation"),
        "spells" to Regex("instant or sorcery|noncreature spell|whenever you cast (an|a) (instant|sorcery)|magecraft|prowess"),
        "landfall" to Regex("landfall|whenever a land (you control )?enters"),
        "attack" to Regex("whenever .{0,30} attacks|attacking creatures|combat damage to a player"),
    )

    fun of(text: String, type: String, keywords: Set<String>): Set<String> {
        val out = HashSet<String>()
        for ((tag, re) in rules) if (re.containsMatchIn(text)) out += tag
        if (type.contains("Artifact")) out += "artifacts"
        if (type.contains("Enchantment")) out += "enchantments"
        if (type.contains("Instant") || type.contains("Sorcery")) out += "spells"
        if ("lifelink" in keywords) out += "lifegain"
        return out
    }

    val generic = setOf("removal", "counter", "wipe", "draw", "ramp")
}

object Scoring {
    private val goodKeywords = setOf("flying", "lifelink", "deathtouch", "trample", "haste", "vigilance",
        "first strike", "double strike", "flash", "ward", "menace", "hexproof", "indestructible")

    fun quality(p: PoolCard): Double {
        if (p.isLand) return p.card.edhrecRank?.let { rankScore(it) } ?: 2.0
        var q = p.card.edhrecRank?.let { rankScore(it) } ?: 3.0
        q += when (p.card.rarity) { "mythic" -> 0.8; "rare" -> 0.6; "uncommon" -> 0.3; else -> 0.0 }
        if (p.isCreature) {
            val pw = p.card.power?.toDoubleOrNull()
            val tg = p.card.toughness?.toDoubleOrNull()
            if (pw != null && tg != null) {
                val eff = (pw + tg) / max(1.0, p.cmc)
                q += when { eff >= 2.5 -> 0.9; eff >= 2.0 -> 0.6; eff >= 1.5 -> 0.3; else -> 0.0 }
            }
        }
        q += min(1.2, p.keywords.count { it in goodKeywords } * 0.3)
        if ("removal" in p.tags) q += 1.5
        if ("wipe" in p.tags) q += 0.8
        if ("draw" in p.tags) q += 0.8
        if ("counter" in p.tags) q += 0.6
        if ("ramp" in p.tags) q += 0.4
        if (p.cmc >= 7) q -= 2.0 else if (p.cmc >= 6) q -= 0.8
        return q
    }

    private fun rankScore(rank: Int) = 10.0 * (1 - ln(max(1, rank).toDouble()) / ln(30000.0)).coerceIn(0.0, 1.0)
}

data class DeckEntry(
    val name: String,
    val quantity: Int,
    val section: String,
    val typeLine: String,
    val manaCost: String,
    val cmc: Double,
    val imageUrl: String?,
    val priceUsd: Double? = null,
)

data class BuiltDeck(
    val format: Format,
    val name: String,
    val colors: String,
    val commander: CardData?,
    val entries: List<DeckEntry>,
    val score: Double,
    val complete: Boolean,
    val warnings: List<String>,
    val description: String,
) {
    val cardCount get() = entries.sumOf { it.quantity }
}

data class BuildOptions(
    val colors: Set<Char> = emptySet(),
    val commanderName: String? = null,
    val buildAround: String? = null,
    val assumeBasics: Boolean = true,
    val maxResults: Int = 6,
)

class DeckBuilder(collection: List<CollectionCard>) {
    private val grouped: Map<String, List<CollectionCard>> = collection.groupBy { it.card.name }

    fun pool(format: Format): List<PoolCard> = grouped.mapNotNull { (_, items) ->
        val rep = items.firstOrNull { it.card.imageUrlLarge != null } ?: items.first()
        val leg = rep.card.legalityMap()
        val l = leg[format.id] ?: "not_legal"
        if (l != "legal" && l != "restricted") null
        else PoolCard(rep.card, items.sumOf { it.quantity }, l, leg)
    }

    /** Commanders/oathbreakers in the collection that are valid for [format]. */
    fun commanderCandidates(format: Format): List<PoolCard> =
        pool(format).filter { isCommander(it, format) }.sortedBy { it.name }

    fun build(format: Format, opts: BuildOptions): List<BuiltDeck> {
        val pool = pool(format)
        return if (format.hasCommander) buildCommanderDecks(format, pool, opts)
        else buildConstructedDecks(format, pool, opts)
    }

    // ------------------------------------------------------------------ helpers

    private fun copies(p: PoolCard, f: Format, opts: BuildOptions): Int {
        val limit = when {
            p.isBasic -> if (opts.assumeBasics) 99 else p.owned
            p.text.contains("a deck can have any number of cards named") -> p.owned
            p.text.contains("a deck can have up to seven cards named") -> 7
            f.singleton -> 1
            p.legality == "restricted" -> 1
            else -> 4
        }
        return if (p.isBasic && opts.assumeBasics) limit else min(limit, p.owned)
    }

    /** How many copies we *want* in a 60-card deck (avoid 4x legendary / 4x expensive). */
    private fun desiredCopies(p: PoolCard, f: Format, opts: BuildOptions): Int {
        val c = copies(p, f, opts)
        if (f.singleton) return min(1, c)
        val want = when {
            p.isLegendary && !p.isLand -> 2
            p.cmc >= 5 -> 2
            p.cmc >= 4 -> 3
            else -> 4
        }
        return min(c, want)
    }

    private fun isCommander(p: PoolCard, f: Format): Boolean {
        val t = p.type
        val canBe = p.text.contains("can be your commander")
        return when (f.commander) {
            CommanderRule.NONE -> false
            CommanderRule.COMMANDER -> canBe || (t.contains("Legendary") && t.contains("Creature"))
            CommanderRule.BRAWL -> canBe || (t.contains("Legendary") && (t.contains("Creature") || t.contains("Planeswalker")))
            CommanderRule.OATHBREAKER -> t.contains("Planeswalker")
            CommanderRule.PAUPER_COMMANDER -> t.contains("Creature")
        }
    }

    private fun curveCaps(target: Int, commander: Boolean): IntArray {
        // index 1..6 = mana value 0-1, 2, 3, 4, 5, 6+
        val base = if (commander) intArrayOf(0, 10, 16, 15, 11, 7, 6) else intArrayOf(0, 8, 12, 10, 7, 4, 3)
        val ref = if (commander) 63.0 else 37.0
        return IntArray(7) { if (it == 0) 0 else max(1, (base[it] * target / ref).roundToInt()) }
    }

    private fun pickSpells(
        cands: List<PoolCard>,
        score: (PoolCard) -> Double,
        target: Int,
        f: Format,
        opts: BuildOptions,
        caps: IntArray,
        minCreatures: Int,
        tagMinimums: Map<String, Int>,
        forced: PoolCard?,
    ): LinkedHashMap<PoolCard, Int> {
        val sorted = cands.sortedByDescending(score)
        val picks = LinkedHashMap<PoolCard, Int>()
        val buckets = IntArray(7)
        var total = 0
        fun have(p: PoolCard) = picks[p] ?: 0
        fun add(p: PoolCard, n: Int) {
            if (n <= 0) return
            picks[p] = have(p) + n; total += n; buckets[p.bucket] += n
        }
        fun room(p: PoolCard, respectCurve: Boolean): Int {
            var n = min(desiredCopies(p, f, opts) - have(p), target - total)
            if (respectCurve) n = min(n, caps[p.bucket] - buckets[p.bucket])
            return max(0, n)
        }

        if (forced != null) add(forced, room(forced, false))
        for ((tag, minCount) in tagMinimums) {
            var count = picks.entries.filter { tag in it.key.tags }.sumOf { it.value }
            for (p in sorted) {
                if (count >= minCount || total >= target) break
                if (tag !in p.tags) continue
                val n = room(p, true)
                add(p, n); count += n
            }
        }
        var creatures = picks.entries.filter { it.key.isCreature }.sumOf { it.value }
        for (p in sorted) {
            if (creatures >= minCreatures || total >= target) break
            if (!p.isCreature) continue
            val n = room(p, true)
            add(p, n); creatures += n
        }
        for (p in sorted) { if (total >= target) break; add(p, room(p, true)) }
        for (p in sorted) { if (total >= target) break; add(p, room(p, false)) }
        // last resort in 60-card decks: allow up to the legal maximum of copies
        if (total < target && !f.singleton) for (p in sorted) {
            if (total >= target) break
            add(p, max(0, min(copies(p, f, opts) - have(p), target - total)))
        }
        return picks
    }

    private fun producedColors(p: PoolCard, deckColors: Set<Char>): Set<Char> {
        val produced = p.card.producedMana.filter { it in "WUBRG" }.toSet()
        if (produced.isNotEmpty()) return produced
        if (p.text.contains("search your library for")) {
            if (p.text.contains("basic land card")) return deckColors
            val m = mutableSetOf<Char>()
            if (p.text.contains("plains")) m += 'W'
            if (p.text.contains("island")) m += 'U'
            if (p.text.contains("swamp")) m += 'B'
            if (p.text.contains("mountain")) m += 'R'
            if (p.text.contains("forest")) m += 'G'
            return m
        }
        return emptySet()
    }

    private val basicNames = mapOf('W' to "Plains", 'U' to "Island", 'B' to "Swamp", 'R' to "Mountain", 'G' to "Forest")

    private fun pickLands(
        pool: List<PoolCard>, colors: Set<Char>, count: Int, f: Format, opts: BuildOptions,
        spells: Map<PoolCard, Int>, warnings: MutableList<String>,
    ): List<DeckEntry> {
        val out = mutableListOf<DeckEntry>()
        var remaining = count
        val multi = colors.size >= 2
        val commanderFmt = f.hasCommander

        data class Cand(val p: PoolCard, val score: Double, val dual: Boolean)
        val cands = pool.filter { it.isLand && !it.isBasic }.mapNotNull { p ->
            if (!commanderFmt && p.text.contains("commander")) return@mapNotNull null
            if (commanderFmt && !colors.containsAll(p.identity)) return@mapNotNull null
            val prod = producedColors(p, colors)
            val on = prod.intersect(colors)
            val offIdentity = !colors.containsAll(p.identity)
            when {
                multi && on.size >= 2 -> Cand(p, 5.0 + on.size + p.quality * 0.3, true)
                offIdentity -> null
                on.isNotEmpty() || prod.isEmpty() -> Cand(p, p.quality * 0.5, false)
                else -> null
            }
        }.sortedByDescending { it.score }

        val dualCap = if (commanderFmt) (count * 0.6).toInt() else if (multi) count / 3 + 1 else 0
        val utilCap = when {
            commanderFmt -> if (multi) 5 else 8
            multi -> 2
            else -> 4
        }
        var duals = 0; var utils = 0
        for (c in cands) {
            if (remaining <= 0) break
            val n = if (c.dual) min(copies(c.p, f, opts), min(dualCap - duals, remaining))
            else min(min(copies(c.p, f, opts), if (f.singleton) 1 else 2), min(utilCap - utils, remaining))
            if (n <= 0) continue
            if (c.dual) duals += n else utils += n
            remaining -= n
            out += entry(c.p, n)
        }

        if (remaining > 0) {
            if (colors.isEmpty()) {
                out += basicEntry(pool, "Wastes", remaining)
                if (!opts.assumeBasics) warnings += "Uses $remaining Wastes (assumed owned)"
                return out
            }
            // distribute basics by colored mana symbols in the spells
            val pips = HashMap<Char, Double>()
            val sym = Regex("\\{([^\\}]+)\\}")
            for ((p, n) in spells) for (m in sym.findAll(p.card.manaCost)) {
                val cs = m.groupValues[1].filter { it in "WUBRG" }
                for (c in cs) pips[c] = (pips[c] ?: 0.0) + n.toDouble() / cs.length
            }
            for (c in colors) if ((pips[c] ?: 0.0) <= 0.0) pips[c] = 1.0
            val totalPips = colors.sumOf { pips[it] ?: 0.0 }
            val alloc = colors.associateWith { ((pips[it] ?: 0.0) / totalPips * remaining).toInt() }.toMutableMap()
            var left = remaining - alloc.values.sum()
            for (c in colors.sortedByDescending { (pips[it] ?: 0.0) / totalPips * remaining - (alloc[it] ?: 0) }) {
                if (left <= 0) break
                alloc[c] = alloc[c]!! + 1; left--
            }
            for ((c, n) in alloc) {
                if (n <= 0) continue
                val bname = basicNames[c]!!
                var use = n
                if (!opts.assumeBasics) {
                    val owned = pool.filter { it.isBasic && it.name == bname }.sumOf { it.owned }
                    if (owned < n) { warnings += "Need ${n - owned} more $bname"; use = owned }
                }
                if (use > 0) out += basicEntry(pool, bname, use)
            }
        }
        return out
    }

    private fun entry(p: PoolCard, n: Int, section: String = "main") = DeckEntry(
        p.card.name, n, section, p.card.typeLine, p.card.manaCost, p.card.cmc, p.card.imageUrlLarge ?: p.card.imageUrl,
        p.card.priceUsd
    )

    private fun basicEntry(pool: List<PoolCard>, name: String, n: Int): DeckEntry {
        val owned = pool.firstOrNull { it.name == name }
        return DeckEntry(name, n, "main", owned?.card?.typeLine ?: "Basic Land — $name", "", 0.0,
            owned?.card?.imageUrlLarge, owned?.card?.priceUsd)
    }

    private fun dominantTribe(cards: List<PoolCard>): String? {
        val counts = HashMap<String, Int>()
        for (p in cards) if (p.isCreature) for (s in p.subtypes) counts[s] = (counts[s] ?: 0) + 1
        val ignore = setOf("human", "wizard", "warrior", "soldier", "shaman", "cleric", "rogue", "knight")
        val best = counts.filter { it.key !in ignore }.maxByOrNull { it.value } ?: return null
        val lords = cards.count { it.text.contains(best.key) }
        return if (best.value >= 8 && lords >= 2) best.key else null
    }

    private fun summary(spells: Map<PoolCard, Int>, landCount: Int): String {
        val creatures = spells.entries.filter { it.key.isCreature }.sumOf { it.value }
        val n = spells.values.sum()
        val avg = if (n == 0) 0.0 else spells.entries.sumOf { it.key.cmc * it.value } / n
        return "$landCount lands · $creatures creatures · ${n - creatures} other spells · avg MV ${"%.2f".format(avg)}"
    }

    private fun pickDistinct(decks: List<BuiltDeck>, max: Int): List<BuiltDeck> {
        val sorted = decks.sortedWith(compareByDescending<BuiltDeck> { it.complete }.thenByDescending { it.score })
        val chosen = mutableListOf<BuiltDeck>()
        for (d in sorted) {
            val names = d.entries.filter { !it.typeLine.contains("Land") }.map { it.name }.toSet()
            val dup = chosen.any { c ->
                val other = c.entries.filter { !it.typeLine.contains("Land") }.map { it.name }.toSet()
                names.isNotEmpty() && names.intersect(other).size.toDouble() / names.size > 0.7
            }
            if (!dup) chosen += d
            if (chosen.size >= max) break
        }
        return chosen
    }

    private fun findCard(pool: List<PoolCard>, name: String?): PoolCard? {
        if (name.isNullOrBlank()) return null
        return pool.firstOrNull { it.name.equals(name, true) || it.card.frontName.equals(name, true) }
    }

    // ------------------------------------------------------------------ constructed

    private fun buildConstructedDecks(f: Format, pool: List<PoolCard>, opts: BuildOptions): List<BuiltDeck> {
        val around = findCard(pool, opts.buildAround)
        val combos = if (opts.colors.isNotEmpty()) listOf(opts.colors)
        else allCombos().filter { around == null || it.containsAll(around.identity) }
        val decks = combos.mapNotNull { buildConstructed(f, pool, it, opts, around) }
        return pickDistinct(decks, opts.maxResults)
    }

    private fun allCombos(): List<Set<Char>> {
        val c = "WUBRG"
        val out = mutableListOf<Set<Char>>()
        for (mask in 1 until 32) {
            val s = c.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            if (s.size <= 3) out += s
        }
        return out
    }

    private fun buildConstructed(f: Format, pool: List<PoolCard>, colors: Set<Char>, opts: BuildOptions, around: PoolCard?): BuiltDeck? {
        val size = f.deckSize
        val nonlands = pool.filter { !it.isLand && colors.containsAll(it.identity) }
        val minPerColor = if (size >= 100) 8 else 4
        for (c in colors) if (nonlands.count { c in it.identity } < minPerColor) return null
        val tribe = dominantTribe(nonlands)
        fun score(p: PoolCard): Double {
            var s = p.quality
            if (tribe != null) {
                if (tribe in p.subtypes) s += 1.5
                if (p.text.contains(tribe)) s += 2.0
            }
            if (around != null && p.tags.intersect(around.tags - Tags.generic).isNotEmpty()) s += 1.0
            return s
        }
        val scale = size / 60.0
        var lands = (23 * scale).roundToInt()
        var spells: LinkedHashMap<PoolCard, Int>
        var iteration = 0
        while (true) {
            val target = size - lands
            spells = pickSpells(
                nonlands, ::score, target, f, opts, curveCaps(target, false),
                minCreatures = (target * 0.35).toInt(),
                tagMinimums = mapOf("removal" to (target * 0.15).toInt()),
                forced = around?.takeIf { !it.isLand },
            )
            val n = spells.values.sum()
            val avg = if (n == 0) 3.0 else spells.entries.sumOf { it.key.cmc * it.value } / n
            // Fewer lands for low curves, more for high curves.
            val desired = ((17 + 2.3 * avg).roundToInt().coerceIn(20, 26) * scale).roundToInt()
            if (desired == lands || iteration++ >= 2) break
            lands = desired
        }
        val warnings = mutableListOf<String>()
        val picked = spells.values.sum()
        var shortfall = size - lands - picked
        var complete = true
        if (shortfall in 1..2) { lands += shortfall; shortfall = 0 }
        if (shortfall > 0) {
            complete = false
            warnings += "Only $picked playable spells in these colors — $shortfall short of a full deck"
        }
        val landEntries = pickLands(pool, colors, lands, f, opts, spells, warnings)
        if (warnings.any { it.startsWith("Need") }) complete = false

        val creatures = spells.entries.filter { it.key.isCreature }.sumOf { it.value }
        val spellish = spells.entries.filter { it.key.type.contains("Instant") || it.key.type.contains("Sorcery") }.sumOf { it.value }
        val interaction = spells.entries.filter { "counter" in it.key.tags || "removal" in it.key.tags }.sumOf { it.value }
        val avg = if (picked == 0) 0.0 else spells.entries.sumOf { it.key.cmc * it.value } / picked
        val archetype = when {
            tribe != null -> pluralTribe(tribe)
            around != null -> around.card.frontName
            creatures >= picked * 0.5 && avg <= 2.6 -> "Aggro"
            spellish >= picked * 0.45 && interaction >= picked * 0.3 -> "Control"
            spellish >= picked * 0.45 -> "Spells"
            else -> "Midrange"
        }
        val colorStr = "WUBRG".filter { it in colors }
        val avgScore = spells.entries.sumOf { score(it.key) * it.value } / max(1, size - lands)
        val total = avgScore - (colors.size - 1) * 0.25 - shortfall * 0.5
        val entries = spells.map { (p, n) -> entry(p, n) } + landEntries
        return BuiltDeck(
            format = f, name = "${colorName(colorStr)} $archetype", colors = colorStr, commander = null,
            entries = entries, score = total, complete = complete, warnings = warnings,
            description = summary(spells, landEntries.sumOf { it.quantity }),
        )
    }

    // ------------------------------------------------------------------ commander

    private fun buildCommanderDecks(f: Format, pool: List<PoolCard>, opts: BuildOptions): List<BuiltDeck> {
        val around = findCard(pool, opts.buildAround)
        var cands = pool.filter { isCommander(it, f) }
        val chosen = findCard(cands, opts.commanderName)
        if (chosen != null) cands = listOf(chosen)
        else {
            if (around != null) cands = cands.filter { it.identity.containsAll(around.identity) }
            if (opts.colors.isNotEmpty()) cands = cands.filter { it.identity == opts.colors }
            // Pre-rank commanders by card quality and how many owned cards fit their colors.
            val mainPool = mainPool(f, pool)
            cands = cands.sortedByDescending { c ->
                c.quality + ln(1.0 + mainPool.count { !it.isLand && c.identity.containsAll(it.identity) })
            }.take(25)
        }
        val decks = cands.mapNotNull { buildCommander(f, pool, it, opts, around) }
        return pickDistinct(decks, opts.maxResults)
    }

    private fun mainPool(f: Format, pool: List<PoolCard>): List<PoolCard> =
        if (f.commander == CommanderRule.PAUPER_COMMANDER)
            pool.filter { (it.legalities["pauper"] ?: "not_legal") != "not_legal" } // printed at common
        else pool

    private fun buildCommander(f: Format, pool: List<PoolCard>, cmdr: PoolCard, opts: BuildOptions, around: PoolCard?): BuiltDeck? {
        val identity = cmdr.identity
        val main = mainPool(f, pool)
        var cands = main.filter { it !== cmdr && !it.isLand && identity.containsAll(it.identity) }

        val themes = cmdr.tags - Tags.generic
        val tribes = cands.flatMap { if (it.isCreature) it.subtypes else emptySet() }.toSet()
            .filter { t -> t.length > 2 && Regex("\\b${Regex.escape(t)}s?\\b").containsMatchIn(cmdr.text) }.toSet()
        fun score(p: PoolCard): Double {
            var s = p.quality
            s += 1.8 * min(3, p.tags.intersect(themes).size)
            if (p.subtypes.any { it in tribes }) s += 3.0
            if (tribes.any { p.text.contains(it) }) s += 1.5
            if (p.keywords.intersect(cmdr.keywords).isNotEmpty()) s += 0.5
            if (around != null && p === around) s += 100.0
            return s
        }

        var signature: PoolCard? = null
        if (f.commander == CommanderRule.OATHBREAKER) {
            signature = cands.filter { it.type.contains("Instant") || it.type.contains("Sorcery") }.maxByOrNull(::score)
                ?: return null
            cands = cands.filter { it !== signature }
        }

        val size = f.deckSize
        var lands = when {
            size >= 100 -> 36
            f.commander == CommanderRule.OATHBREAKER -> 23
            else -> 24
        }
        val target = size - 1 - lands - (if (signature != null) 1 else 0)
        val s = target / 63.0
        val mins = linkedMapOf(
            "ramp" to (10 * s).roundToInt(), "draw" to (9 * s).roundToInt(),
            "removal" to (7 * s).roundToInt(), "wipe" to if (size >= 100) 2 else 1,
        )
        val spells = pickSpells(
            cands, ::score, target, f, opts, curveCaps(target, true),
            minCreatures = (target * 0.3).toInt(), tagMinimums = mins,
            forced = around?.takeIf { it !== cmdr && it !== signature && !it.isLand && identity.containsAll(it.identity) },
        )
        val warnings = mutableListOf<String>()
        val picked = spells.values.sum()
        var shortfall = target - picked
        var complete = true
        val extraLandAllowance = if (size >= 100) 4 else 2
        if (shortfall in 1..extraLandAllowance) { lands += shortfall; shortfall = 0 }
        if (shortfall > 0) {
            complete = false
            warnings += "Only $picked playable cards in ${colorName("WUBRG".filter { it in identity })} — $shortfall short"
        }
        val landEntries = pickLands(pool, identity, lands, f, opts, spells, warnings)
        if (warnings.any { it.startsWith("Need") }) complete = false

        val ramp = spells.keys.count { "ramp" in it.tags }
        val draw = spells.keys.count { "draw" in it.tags }
        val removal = spells.keys.count { "removal" in it.tags || "wipe" in it.tags || "counter" in it.tags }
        if (ramp < mins["ramp"]!! / 2) warnings += "Low on ramp ($ramp)"
        if (draw < mins["draw"]!! / 2) warnings += "Low on card draw ($draw)"
        if (removal < mins["removal"]!! / 2) warnings += "Low on interaction ($removal)"

        val avgScore = spells.entries.sumOf { score(it.key) * it.value } / max(1, target)
        val total = avgScore + cmdr.quality * 0.3 - shortfall * 0.3
        val themeLabel = tribes.firstOrNull()?.let { pluralTribe(it) }
            ?: themes.firstOrNull()?.replaceFirstChar { it.uppercase() }
        val entries = buildList {
            add(entry(cmdr, 1, "commander"))
            if (signature != null) add(entry(signature, 1, "commander"))
            spells.forEach { (p, n) -> add(entry(p, n)) }
            addAll(landEntries)
        }
        return BuiltDeck(
            format = f,
            name = cmdr.card.frontName + (themeLabel?.let { " — $it" } ?: ""),
            colors = "WUBRG".filter { it in identity },
            commander = cmdr.card,
            entries = entries, score = total, complete = complete, warnings = warnings,
            description = (if (signature != null) "Signature spell: ${signature.name}\n" else "") +
                summary(spells, landEntries.sumOf { it.quantity }) + " · ramp $ramp · draw $draw · interaction $removal",
        )
    }

    companion object {
        private val names = mapOf(
            "W" to "Mono-White", "U" to "Mono-Blue", "B" to "Mono-Black", "R" to "Mono-Red", "G" to "Mono-Green",
            "WU" to "Azorius", "UB" to "Dimir", "BR" to "Rakdos", "RG" to "Gruul", "WG" to "Selesnya",
            "WB" to "Orzhov", "UR" to "Izzet", "BG" to "Golgari", "WR" to "Boros", "UG" to "Simic",
            "WUB" to "Esper", "UBR" to "Grixis", "BRG" to "Jund", "WRG" to "Naya", "WUG" to "Bant",
            "WBG" to "Abzan", "WUR" to "Jeskai", "UBG" to "Sultai", "WBR" to "Mardu", "URG" to "Temur",
            "" to "Colorless", "WUBRG" to "5-Color",
        )

        fun colorName(c: String) = names[c] ?: "4-Color"
    }
}

private val tribePlurals = mapOf(
    "elf" to "Elves", "dwarf" to "Dwarves", "wolf" to "Wolves", "werewolf" to "Werewolves",
    "fox" to "Foxes", "sphinx" to "Sphinxes", "ox" to "Oxen", "faerie" to "Faeries",
    "fungus" to "Fungi", "octopus" to "Octopi", "mouse" to "Mice", "human" to "Humans",
    "merfolk" to "Merfolk", "sliver" to "Slivers", "kithkin" to "Kithkin", "moonfolk" to "Moonfolk",
)

internal fun pluralTribe(t: String): String {
    val key = t.lowercase()
    tribePlurals[key]?.let { return it }
    val cap = t.replaceFirstChar { it.uppercase() }
    return when {
        key.endsWith("folk") -> cap
        key.endsWith("s") || key.endsWith("x") || key.endsWith("ch") || key.endsWith("sh") -> cap + "es"
        key.endsWith("y") && key.length > 2 && key[key.length - 2] !in "aeiou" -> cap.dropLast(1) + "ies"
        else -> cap + "s"
    }
}
