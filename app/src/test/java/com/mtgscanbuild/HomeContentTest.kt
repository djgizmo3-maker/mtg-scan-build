package com.mtgscanbuild

import com.mtgscanbuild.data.HomeModes
import com.mtgscanbuild.data.MatchType
import com.mtgscanbuild.data.NewsApi
import com.mtgscanbuild.data.ScryfallApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeContentTest {
    private fun card(id: String, name: String, status: String) = JSONObject()
        .put("id", id).put("name", name).put("scryfall_uri", "https://scryfall.com/card/$id")
        .put("legalities", JSONObject().put("vintage", status).put("modern", "legal"))

    private fun page(vararg cards: JSONObject, next: String? = null) = JSONObject()
        .put("data", JSONArray(cards.toList())).put("has_more", next != null)
        .also { if (next != null) it.put("next_page", next) }

    @Test
    fun newsUsesArticleCardsNotNavigationAndDeduplicatesLinks() {
        val html = """
            <a href="/en/news/announcements">Announcements</a>
            <div data-article-hub-card-v="1.8">
              <h3>New &amp; noteworthy</h3>
              <time datetime="2026-10-08"></time>
              <a href="/en/news/announcements/new-article">Read more</a>
            </div>
            <div data-article-hub-card-v="1.8">
              <h3>Duplicate</h3><a href="/en/news/announcements/new-article">Duplicate</a>
            </div>
            <div data-article-hub-card-v="1.8">
              <h3>Second article</h3><a href="https://magic.wizards.com/en/news/feature/another">Read</a>
            </div>
            <div data-article-hub-card-v="1.8"><h3>External</h3>
              <a href="https://example.com/en/news/feature/other">Read</a>
            </div>
        """.trimIndent()
        val articles = NewsApi.parseNews(html)
        assertEquals(listOf("New & noteworthy", "Second article"), articles.map { it.title })
        assertEquals("https://magic.wizards.com/en/news/announcements/new-article", articles[0].url)
        assertEquals("2026-10-08", articles[0].publishedDate)
        assertNull(articles[1].publishedDate)
    }

    @Test(expected = IOException::class)
    fun missingNewsMarkupIsAnErrorNotAnEmptySuccess() {
        NewsApi.parseNews("<html><body>Service unavailable</body></html>")
    }

    @Test
    fun onlyExplicitBansAndRestrictionsAreIncludedForSelectedFormat() {
        val cards = page(card("1", "Banned", "banned"), card("2", "Restricted", "restricted"),
            card("3", "Never eligible", "not_legal"), card("4", "Legal", "legal"))
        assertEquals(listOf("banned", "restricted"), ScryfallApi.parseLegalityPage(cards, "vintage").map { it.status })
        assertTrue(ScryfallApi.parseLegalityPage(cards, "modern").isEmpty())
    }

    @Test
    fun allPagesAreLoadedSortedDeduplicatedAndSplit() = runBlocking {
        val next = "https://api.scryfall.com/cards/search?page=2"
        val calls = mutableListOf<String>()
        val result = ScryfallApi().loadBanLists("vintage") { url ->
            calls += url
            if (url == next) page(card("3", "Alpha", "banned"), card("2", "Beta", "restricted"))
            else page(card("1", "Zeta", "banned"), card("2", "Beta", "restricted"), next = next)
        }
        assertEquals(2, calls.size)
        assertEquals(next, calls.last())
        assertEquals(listOf("Alpha", "Zeta"), result.banned.map { it.name })
        assertEquals(listOf("Beta"), result.restricted.map { it.name })
        assertTrue(calls.first().contains("banned%3Avintage"))
        assertTrue(calls.first().contains("restricted%3Avintage"))
    }

    @Test
    fun noResultsOnFirstPageProducesEmptyLists() = runBlocking {
        val lists = ScryfallApi().loadBanLists("vintage") { null }
        assertTrue(lists.banned.isEmpty())
        assertTrue(lists.restricted.isEmpty())
    }

    @Test(expected = IOException::class)
    fun missingLaterPageIsNotReportedAsComplete() = runBlocking {
        val next = "https://api.scryfall.com/cards/search?page=2"
        ScryfallApi().loadBanLists("vintage") { url ->
            if (url == next) null else page(card("1", "One", "banned"), next = next)
        }
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun repeatedPaginationFailsRatherThanLooping() = runBlocking {
        val next = "https://api.scryfall.com/cards/search?page=2"
        ScryfallApi().loadBanLists("vintage") { page(card("1", "One", "banned"), next = next) }
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun paginationCannotSendRequestsToAnotherHost() = runBlocking {
        var calls = 0
        try {
            ScryfallApi().loadBanLists("vintage") {
                calls++
                page(card("1", "One", "banned"), next = "https://example.com/cards/search?page=2")
            }
        } finally {
            assertEquals(1, calls)
        }
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFormatCannotBecomeAnEmptyBanList() = runBlocking {
        ScryfallApi().bannedAndRestricted("not-a-format")
        Unit
    }

    @Test
    fun catalogSeparatesMatchTypesAndDoesNotInventLimitedBanLists() {
        assertEquals(HomeModes.all.size, HomeModes.all.map { it.id }.distinct().size)
        assertEquals(MatchType.entries.toSet(), HomeModes.all.map { it.matchType }.toSet())
        assertTrue(HomeModes.all.all { it.rules.isNotEmpty() && it.rulesUrl.startsWith("https://magic.wizards.com/") })
        assertTrue(HomeModes.all.filter { it.matchType == MatchType.LIMITED || it.matchType == MatchType.TEAM }
            .all { it.legalityId == null })
        assertTrue(HomeModes.withBanLists.any { it.id == "vintage" })
        assertTrue(HomeModes.withBanLists.any { it.id == "timeless" })
    }
}
