package com.snas.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.snas.app.catalog.TmdbEpisode
import com.snas.app.catalog.TmdbRepository
import com.snas.app.model.CatalogItem
import com.snas.app.model.MediaType
import com.snas.app.storage.UserStore
import com.snas.app.storage.WatchEntry
import com.snas.app.ui.NavSoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF141414)
private val Accent = Color(0xFFE50914)
private val White = Color.White
private val Dim = Color(0xFFB3B3B3)
private val Muted = Color(0xFF808080)
private val CardBg = Color(0xFF1A1A1A)
private val CardBgFocused = Color(0xFF2A2A2A)

// ── Season/episode cache ──────────────────────────────────────────
private val epCache = mutableMapOf<String, MutableMap<Int, List<TmdbEpisode>>>()

private enum class DetailTab(val label: String) {
    Overview("Overview"),
    Episodes("Episodes"),
    Details("Details"),
}

@Composable
fun TitleDetailsScreen(
    item: CatalogItem,
    onBack: () -> Unit,
    onPlay: (CatalogItem, Int?, Int?) -> Unit,
    continueWatching: List<WatchEntry> = emptyList(),
    userStore: UserStore? = null,
) {
    val lastWatched = continueWatching.firstOrNull { it.imdbId == item.imdbId }
    val isTv = item.type == MediaType.tv
    val seasons = item.seasons.ifEmpty { listOf(1) }
    val initialSeason = if (isTv) lastWatched?.season ?: seasons.firstOrNull() ?: 1 else 1

    var selectedSeason by remember(item.imdbId) { mutableStateOf(initialSeason) }
    var epDataRaw by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var epLoading by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(DetailTab.Overview) }

    // Episodes tab is default for TV shows
    LaunchedEffect(item.imdbId) {
        if (isTv) activeTab = DetailTab.Episodes
    }

    val playSeason = if (isTv) lastWatched?.season ?: seasons.firstOrNull() ?: 1 else null
    val playEpisode = if (isTv) lastWatched?.episode ?: 1 else null
    val artworkUrl = item.resolvedBackdropUrl ?: item.resolvedPosterUrl

    // Load TMDb episode data
    LaunchedEffect(item.imdbId, selectedSeason) {
        if (!isTv) return@LaunchedEffect
        val cached = epCache[item.imdbId]?.get(selectedSeason)
        if (cached != null) { epDataRaw = cached; return@LaunchedEffect }
        epLoading = true
        epDataRaw = emptyList()  // Clear stale data from previous season
        try {
            val fetched = withContext(Dispatchers.IO) {
                TmdbRepository.fetchEpisodes(item.imdbId, selectedSeason)
            }
            epCache.getOrPut(item.imdbId) { mutableMapOf() }[selectedSeason] = fetched
            epDataRaw = fetched
        } catch (_: Exception) {
            // TMDb failed — fall back to catalog data
        } finally {
            epLoading = false
        }
    }

    // Fetch overview from TMDb when catalog plot is null
    var tmdbOverview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.imdbId) {
        if (!item.plot.isNullOrBlank()) return@LaunchedEffect
        tmdbOverview = withContext(Dispatchers.IO) {
            TmdbRepository.fetchOverview(item.imdbId, isTv)
        }
    }
    val effectivePlot = item.plot?.takeIf { it.isNotBlank() } ?: tmdbOverview

    var isFav by remember { mutableStateOf(userStore?.isFavorite(item.imdbId) ?: false) }
    val resumeLabel = if (lastWatched != null && isTv) "Resume S${lastWatched.season}:E${lastWatched.episode}" else null

    // Truncated plot for hero (2 lines max)
    val heroPlot = effectivePlot?.let { if (it.length > 120) it.take(120) + "..." else it }

    val heroFraction = if (isTv) 0.48f else 0.60f

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        // Remote BACK button → return to home (not exit prompt)
        BackHandler { onBack() }
        // ── Hero Backdrop — Netflix-style immersive gradient ──
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(heroFraction)) {
            if (artworkUrl != null) {
                SubcomposeAsyncImage(model = artworkUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // Vertical gradient: transparent top → deep bg at bottom
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Transparent, Bg.copy(alpha = 0.5f),
                        Bg.copy(alpha = 0.85f), Bg)
                )
            ))
            // Left gradient: adds depth on the left edge
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Bg.copy(alpha = 0.4f), Color.Transparent, Color.Transparent, Color.Transparent)
                )
            ))
            // Top vignette
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Bg.copy(alpha = 0.5f), Color.Transparent)
                )
            ))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Hero info ──
            Column(
                modifier = Modifier.weight(heroFraction).padding(start = 56.dp, end = 56.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(item.title, color = White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)
                    ),
                )
                Spacer(Modifier.height(6.dp))
                // Metadata row
                val meta = buildList {
                    item.year?.let { add(it.toString()) }
                    if (isTv && seasons.size > 1) add("${seasons.size} Seasons")
                    if (!isTv) item.runtimeMinutes?.let { add("${it} min") }
                    item.rating?.let { add(String.format("%.1f", it)) }
                }
                Text(meta.joinToString("  \u2022  "), color = Dim, fontSize = 14.sp)
                if (!heroPlot.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(heroPlot, color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                // ── Action row ──
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val playFocus = remember { FocusRequester() }
                    var pf by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { playFocus.requestFocus() }
                    Box(
                        modifier = Modifier
                            .focusRequester(playFocus)
                            .scale(animateFloatAsState(if (pf) 1.05f else 1f, tween(150)).value)
                            .height(42.dp).widthIn(min = 150.dp)
                            .background(if (pf) Accent else Color.White, RoundedCornerShape(6.dp))
                            .onFocusChanged { pf = it.isFocused }
                            .clickable { onPlay(item, playSeason, playEpisode) }
                            .focusable()
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(resumeLabel ?: "\u25B6  Play", color = if (pf) White else Color.Black,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    var ff by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .scale(animateFloatAsState(if (ff) 1.08f else 1f, tween(150)).value).size(42.dp)
                            .background(if (ff) Color(0xFF404040) else Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .border(if (ff) 2.dp else 0.dp, White, RoundedCornerShape(6.dp))
                            .onFocusChanged { ff = it.isFocused }
                            .clickable { userStore?.let { isFav = it.toggleFavorite(item.imdbId) } }
                            .focusable(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Netflix-style: bold red heart when favorited, white outline when not
                        Text(if (isFav) "\u2764" else "\u2661", color = if (isFav) Accent else White,
                            fontSize = 22.sp, fontWeight = if (isFav) FontWeight.Bold else FontWeight.Normal)
                    }
                    var bf by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .scale(animateFloatAsState(if (bf) 1.08f else 1f, tween(150)).value).size(42.dp)
                            .background(if (bf) Color(0xFF404040) else Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .border(if (bf) 2.dp else 0.dp, White, RoundedCornerShape(6.dp))
                            .onFocusChanged { bf = it.isFocused }
                            .clickable { onBack() }
                            .focusable(),
                        contentAlignment = Alignment.Center,
                    ) { Text("\u2190", color = White, fontSize = 17.sp) }
                }
            }

            // ── Tab bar ──
            val tabs = if (isTv) listOf(DetailTab.Overview, DetailTab.Episodes, DetailTab.Details)
                       else listOf(DetailTab.Overview, DetailTab.Details)
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.padding(start = 56.dp, end = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                tabs.forEach { tab ->
                    val active = tab == activeTab
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            tab.label,
                            color = if (active) White else Muted,
                            fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { activeTab = tab }
                                .focusable()
                                .padding(vertical = 6.dp),
                        )
                        if (active) Box(Modifier.width(24.dp).height(2.dp).background(Accent))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Tab content ──
            when (activeTab) {
                DetailTab.Overview -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 56.dp),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        // Metadata row
                        val overviewMeta = buildList {
                            item.year?.let { add(it.toString()) }
                            item.rating?.let { add(String.format("%.1f/10", it)) }
                            if (!isTv) item.runtimeMinutes?.let { add("${it} min") }
                            if (isTv) add("${seasons.size} Season${if (seasons.size != 1) "s" else ""}")
                        }
                        if (overviewMeta.isNotEmpty()) {
                            Text(overviewMeta.joinToString("  \u2022  "), color = Dim, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        // Genre pills
                        if (item.genres.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.genres.take(5).forEach { g ->
                                    Box(
                                        Modifier.background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text(g, color = Dim, fontSize = 12.sp) }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        // Full plot with fallback from TMDb
                        if (!effectivePlot.isNullOrBlank()) {
                            Text(effectivePlot, color = Dim, fontSize = 14.sp, lineHeight = 20.sp,
                                modifier = Modifier.widthIn(max = 700.dp))
                        } else {
                            Text("No overview available.", color = Muted, fontSize = 14.sp)
                        }
                    }
                }
                DetailTab.Episodes -> {
                    // ── Season selector row (prominent, not text tabs) ──
                    Column(modifier = Modifier.weight(1f)) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 52.dp, end = 52.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            seasons.forEachIndexed { idx, s ->
                                val active = s == selectedSeason
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (active) Accent else Color(0xFF2A2A2A))
                                        .clickable { selectedSeason = s }
                                        .focusable()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        "Season $s",
                                        color = if (active) White else Color(0xFFCCCCCC),
                                        fontSize = 15.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    )
                                }
                                if (idx < seasons.lastIndex) {
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // ── Episode scroll ──
                        data class EpInfo(val num: Int, val title: String, val overview: String?, val still: String?)
                        if (epLoading) {
                            Text("Loading episodes...", color = Muted, fontSize = 14.sp,
                                modifier = Modifier.padding(start = 56.dp, top = 20.dp))
                        } else if (epDataRaw.isNotEmpty()) {
                            // Primary: TMDb data
                            val displayed = epDataRaw.mapIndexed { idx, td ->
                                EpInfo(idx + 1, td.name ?: "Episode ${idx + 1}", td.overview, td.still_path)
                            }
                            LazyRow(
                                contentPadding = PaddingValues(start = 56.dp, end = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(displayed.size) { idx ->
                                    val ep = displayed[idx]
                                    EpisodeCard(epNum = ep.num, title = ep.title, overview = ep.overview,
                                        still = ep.still, isActive = lastWatched?.episode == ep.num,
                                        onClick = { onPlay(item, selectedSeason, ep.num) })
                                }
                            }
                        } else {
                            // TMDb returned nothing — fall back to catalog
                            val episodeNums = item.episodeMap[selectedSeason] ?: item.episodes
                            if (episodeNums.isNotEmpty()) {
                                val displayed = episodeNums.map { EpInfo(it, "Episode $it", null, null) }
                                LazyRow(
                                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(displayed.size) { idx ->
                                        val ep = displayed[idx]
                                        EpisodeCard(epNum = ep.num, title = ep.title, overview = ep.overview,
                                            still = ep.still, isActive = lastWatched?.episode == ep.num,
                                            onClick = { onPlay(item, selectedSeason, ep.num) })
                                    }
                                }
                            } else {
                                Text("No episodes found for season $selectedSeason", color = Muted, fontSize = 14.sp,
                                    modifier = Modifier.padding(start = 56.dp, top = 8.dp))
                            }
                        }
                    }
                }
                DetailTab.Details -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 56.dp, top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.year?.let { DetailRow("Year", it.toString()) }
                        if (!isTv) item.runtimeMinutes?.let { DetailRow("Runtime", "${it} min") }
                        if (isTv) DetailRow("Seasons", seasons.size.toString())
                        val totalEps = item.episodeMap.values.sumOf { it.size }
                        if (totalEps > 0) DetailRow("Episodes", "$totalEps total")
                        item.rating?.let { DetailRow("Rating", String.format("%.1f/10", it)) }
                        item.votes?.let { DetailRow("Votes", "%,d".format(it)) }
                        if (item.genres.isNotEmpty()) {
                            DetailRow("Genres", item.genres.joinToString(", "))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text("$label: ", color = Muted, fontSize = 14.sp)
        Text(value, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EpisodeCard(
    epNum: Int, title: String?, overview: String?, still: String?,
    isActive: Boolean, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.04f else 1f, tween(150))

    Column(
        modifier = Modifier
            .scale(sc).width(200.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) CardBgFocused else CardBg)
            .border(if (focused) 2.dp else 0.dp, White, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .focusable(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(112.dp).background(Color(0xFF333333))) {
            if (still != null) {
                SubcomposeAsyncImage(
                    model = "https://image.tmdb.org/t/p/w300$still",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(5.dp)
                    .background(Accent.copy(alpha = 0.9f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) { Text("EP $epNum", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            if (isActive) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(5.dp)
                        .background(Accent, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) { Text("Resume", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Column(modifier = Modifier.padding(7.dp)) {
            Text(
                title ?: "Episode $epNum",
                color = if (focused) White else Dim,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(overview, color = Muted, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            }
        }
    }
}
