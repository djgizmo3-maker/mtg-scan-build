package com.mtgscanbuild

import com.mtgscanbuild.data.HomeModes
import com.mtgscanbuild.data.NewsApi
import com.mtgscanbuild.data.ScryfallApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Live-source checks; require internet. */
class HomeSourcesTest {
    @Test
    fun officialNewsPageHasUsableArticleHeadlines() = runBlocking {
        val news = NewsApi().latest()
        assertTrue(news.isNotEmpty())
        assertTrue(news.all { it.title.isNotBlank() && it.url.startsWith("https://magic.wizards.com/en/news/") })
        assertEquals(news.size, news.map { it.url }.distinct().size)
        println("Read ${news.size} official headlines")
    }

    @Test
    fun everySupportedFormatLoadsAndRestrictionsStaySeparate() = runBlocking {
        val api = ScryfallApi()
        for (mode in HomeModes.withBanLists) {
            val lists = api.bannedAndRestricted(mode.legalityId!!)
            assertTrue(lists.banned.all { it.status == "banned" })
            assertTrue(lists.restricted.all { it.status == "restricted" })
            assertTrue(lists.banned.map { it.id }.intersect(lists.restricted.map { it.id }.toSet()).isEmpty())
            if (mode.id == "vintage" || mode.id == "timeless") assertTrue(lists.restricted.isNotEmpty())
            println("${mode.name}: ${lists.banned.size} banned, ${lists.restricted.size} restricted")
        }
    }
}
