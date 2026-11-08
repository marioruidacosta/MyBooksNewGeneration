package com.mcosta.mybooks.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.YearReview
import com.mcosta.mybooks.ui.LocalPlatformServices
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.Month
import kotlin.math.roundToInt

/** How many covers the review's mosaic shows. */
private const val MOSAIC = 24

/** A year of reading as a single card, made to be shared as a picture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearReviewScreen(viewModel: YearReviewViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val sharer = LocalPlatformServices.current.imageSharer
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sharing by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("${viewModel.year} in review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (sharer != null && review != null) {
                        IconButton(enabled = !sharing, onClick = {
                            sharing = true
                            scope.launch {
                                try {
                                    sharer.share(layer.toImageBitmap(), "mybooks-${viewModel.year}")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Couldn't share: ${e.message ?: "unknown error"}")
                                } finally {
                                    sharing = false
                                }
                            }
                        }) { Icon(Icons.Filled.Share, "Share as a picture") }
                    }
                },
            )
        },
    ) { padding ->
        val r = review
        if (r == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Drawn into [layer] too, which Share turns into the picture.
            Box(
                Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
            ) {
                ReviewCard(r, onOpenBook)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewCard(r: YearReview, onOpenBook: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLow).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text("My ${r.year} in books", style = MaterialTheme.typography.headlineMedium)
            if (r.books.isEmpty()) {
                Text("No books finished in ${r.year}.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (r.books.isEmpty()) return@Column

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigNumber(r.stats.books.toString(), if (r.stats.books == 1) "book" else "books", Modifier.weight(1f))
            BigNumber(groupedNumber(r.stats.pages), "pages", Modifier.weight(1f))
            r.stats.averageScore?.let { BigNumber("${(it * 10).roundToInt() / 10.0} ★", "average", Modifier.weight(1f)) }
        }

        r.goal?.let { goal ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val percent = r.stats.books * 100 / goal
                Text(
                    if (r.stats.books >= goal) "Reading challenge done: ${r.stats.books} of $goal 🎉" else "Reading challenge: ${r.stats.books} of $goal ($percent%)",
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(progress = { (r.stats.books.toFloat() / goal).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (r.favourites.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Favourites")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (b in r.favourites) {
                        Column(Modifier.weight(1f).clickable { onOpenBook(b.id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BookCover(b.coverUrl, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(6.dp)), title = b.title, authors = b.authors)
                            Text(b.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            RatingStars(b.rating, onRate = null, size = 14.dp)
                        }
                    }
                    // Keep covers the same size when there are fewer than three.
                    repeat(3 - r.favourites.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.longest?.let { Fact("Longest", "${it.title} · ${groupedNumber(it.pageCount ?: 0)} pages") }
            r.shortest?.let { Fact("Shortest", "${it.title} · ${it.pageCount} pages") }
            r.busiestMonth?.let { m ->
                val n = r.stats.perMonth[m - 1]
                Fact("Busiest month", "${reviewMonth(m)} · $n ${if (n == 1) "book" else "books"}")
            }
            r.stats.topAuthors.firstOrNull()?.takeIf { it.second > 1 }?.let { (name, n) -> Fact("Most read author", "$name · $n books") }
            r.first?.let { Fact("First of the year", it.title) }
            r.last?.takeIf { it != r.first }?.let { Fact("Latest", it.title) }
        }

        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in r.books.takeLast(MOSAIC)) {
                BookCover(b.coverUrl, Modifier.width(44.dp).height(66.dp).clip(RoundedCornerShape(3.dp)), title = b.title, authors = b.authors)
            }
        }
        if (r.books.size > MOSAIC) {
            Text("and ${r.books.size - MOSAIC} more", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Text(
            "MyBooks",
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Label(label)
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


private fun reviewMonth(month: Int): String = Month(month).name.lowercase().replaceFirstChar { it.uppercase() }
