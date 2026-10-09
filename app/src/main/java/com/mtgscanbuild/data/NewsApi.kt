package com.mtgscanbuild.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class NewsApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun latest(): List<NewsArticle> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(HomeSources.NEWS)
            .header("User-Agent", "MTGScanBuild/1.0")
            .header("Accept", "text/html").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Wizards news returned HTTP ${response.code}")
            parseNews(response.body?.string() ?: throw IOException("Wizards news returned an empty response"))
        }
    }

    companion object {
        fun parseNews(html: String): List<NewsArticle> {
            val document = Jsoup.parse(html, HomeSources.NEWS)
            val articles = document.select("[data-article-hub-card-v]").mapNotNull { element ->
                val link = element.selectFirst("a[href*=/en/news/]") ?: return@mapNotNull null
                val title = element.selectFirst("h3, h2")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: link.text().trim()
                val url = link.absUrl("href")
                val uri = URI(url)
                if (title.isEmpty() || uri.scheme != "https" || uri.host != "magic.wizards.com" ||
                    !uri.path.matches(Regex("/en/news/[^/]+/.+"))) return@mapNotNull null
                NewsArticle(title, url, element.selectFirst("time[datetime]")?.attr("datetime")
                    ?.takeIf { it.isNotBlank() })
            }.distinctBy { it.url }
            if (articles.isEmpty()) throw IOException("Wizards news could not be read. Open the official news page or try again later.")
            // Keep the publisher's featured/latest ordering; not every article includes a date.
            return articles.take(20)
        }
    }
}
