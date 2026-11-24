package com.example.youtube

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YouTubeProvider())
    }
}

class YouTubeProvider : MainAPI() {
    override var mainUrl = "https://kavin.rocks"
    override var name = "YouTube (Piped)"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/trending?region=IN"
        val items = app.get(url).parsedSafe<List<PipedVideoItem>>() ?: emptyList()

        val homeItems = items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse("Trending", homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.encodeUri()}&filter=videos"
        val response = app.get(url).parsedSafe<PipedSearchResponse>() ?: return emptyList()

        return response.items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = app.get(url).parsedSafe<PipedStreamResponse>()
            ?: throw ErrorLoadingException("Failed to load video metadata")

        return newMovieLoadResponse(
            name = data.title ?: "Unknown Title",
            url = url,
            type = TvType.Others,
            dataUrl = url
        ) {
            this.posterUrl = data.thumbnailUrl
            this.plot = "Channel: ${data.uploader}\n\n${data.description ?: ""}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = app.get(data).parsedSafe<PipedStreamResponse>() ?: return false

        streamData.hls?.let { hlsUrl ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - HLS (Auto/HD)",
                    url = hlsUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        streamData.videoStreams?.filter { it.videoOnly == false && !it.url.isNullOrBlank() }?.forEach { stream ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - ${stream.quality ?: "MP4"}",
                    url = stream.url!!,
                    referer = mainUrl,
                    quality = getQualityFromName(stream.quality),
                    isM3u8 = false
                )
            )
        }

        streamData.subtitles?.forEach { sub ->
            if (!sub.url.isNullOrBlank()) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub.name ?: sub.code ?: "English",
                        url = sub.url
                    )
                )
            }
        }

        return true
    }

    private fun PipedVideoItem.toSearchResponse(): SearchResponse? {
        val videoId = this.url?.substringAfter("v=")?.substringBefore("&") ?: return null
        val title = this.title ?: return null

        return newMovieSearchResponse(
            name = title,
            url = "$mainUrl/streams/$videoId",
            type = TvType.Others
        ) {
            this.posterUrl = this@toSearchResponse.thumbnail
        }
    }

    data class PipedSearchResponse(
        @JsonProperty("items") val items: List<PipedVideoItem> = emptyList()
    )

    data class PipedVideoItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("uploaderName") val uploaderName: String? = null
    )

    data class PipedStreamResponse(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("uploader") val uploader: String? = null,
        @JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null,
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("videoStreams") val videoStreams: List<PipedVideoStream>? = null,
        @JsonProperty("subtitles") val subtitles: List<PipedSubtitle>? = null
    )

    data class PipedVideoStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("videoOnly") val videoOnly: Boolean? = null
    )

    data class PipedSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("code") val code: String? = null
    )
}
