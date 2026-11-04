package com.mcosta.mybooks.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.YearStats
import kotlinx.datetime.Month
import kotlin.math.roundToInt

/** Bar colours for charts: the book teal, stepped to read as data on each surface (validated, light and dark). */
@Composable
private fun chartBarColor(): Color = if (isSystemInDarkTheme()) Color(0xFF2BA89C) else Color(0xFF0A8F84)

private fun monthName(index: Int) = Month.entries[index].name.lowercase().replaceFirstChar { it.uppercase() }

private fun books(n: Int) = if (n == 1) "1 book" else "$n books"

/** 12345 → "12 345". */
internal fun groupedNumber(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

/** "vs 2025: +5", "vs 2025: −2", "Same as 2025". */
internal fun comparedText(stats: YearStats): String {
    val difference = stats.books - stats.previousYearBooks
    return when {
        difference > 0 -> "vs ${stats.year - 1}: +$difference"
        difference < 0 -> "vs ${stats.year - 1}: −${-difference}"
        else -> "Same as ${stats.year - 1}"
    }
}

/** A year of reading: year chips, headline numbers, books per month, and the top authors and publishers. */
@Composable
fun StatsSection(years: List<Int>, stats: YearStats, onYear: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { year -> FilterChip(selected = year == stats.year, onClick = { onYear(year) }, label = { Text("$year") }) }
        }
        if (stats.books == 0) {
            Text(
                "No books finished in ${stats.year}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Books", "${stats.books}", comparedText(stats), Modifier.weight(1f))
            StatTile(
                "Pages",
                groupedNumber(stats.pages),
                if (stats.withoutPages > 0) "${books(stats.withoutPages)} without a page count" else "",
                Modifier.weight(1f),
            )
            StatTile(
                "Average score",
                stats.averageScore?.let { ((it * 10).roundToInt() / 10.0).toString() } ?: "–",
                if (stats.rated > 0) "from ${stats.rated} rated" else "none rated",
                Modifier.weight(1f),
                star = stats.averageScore != null,
            )
        }
        MonthlyBars(stats, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TopList("Top authors", stats.topAuthors, Modifier.weight(1f))
            TopList("Top publishers", stats.topPublishers, Modifier.weight(1f))
        }
    }
}

/** A headline number with a short note under it. */
@Composable
private fun StatTile(label: String, value: String, note: String, modifier: Modifier = Modifier, star: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                if (star) Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/**
 * Books per month: one series, so one colour and no legend (the title names it). Thin bars with rounded tops on
 * the baseline, the busiest month labelled, and a tap on any bar names its month and count.
 */
@Composable
private fun MonthlyBars(stats: YearStats, modifier: Modifier = Modifier) {
    var selected by remember(stats.year) { mutableStateOf<Int?>(null) }
    val bar = chartBarColor()
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val measurer = rememberTextMeasurer()
    val max = stats.perMonth.max().coerceAtLeast(1)
    val busiest = stats.perMonth.indexOf(stats.perMonth.max())
    val description = "Books finished per month in ${stats.year}: " +
        stats.perMonth.mapIndexed { i, n -> "${monthName(i)} $n" }.joinToString(", ")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Books per month", style = MaterialTheme.typography.titleSmall)
        Text(
            selected?.let { "${monthName(it)}: ${books(stats.perMonth[it])}" } ?: "Tap a bar for its month",
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
        )
        Canvas(
            Modifier.fillMaxWidth().height(140.dp)
                .semantics { contentDescription = description }
                .pointerInput(stats) {
                    detectTapGestures { offset -> selected = (offset.x / (size.width / 12f)).toInt().coerceIn(0, 11) }
                },
        ) {
            val slot = size.width / 12f
            val barWidth = slot * 0.6f
            val top = 18.dp.toPx()
            val bottom = size.height
            val radius = CornerRadius(4.dp.toPx())
            stats.perMonth.forEachIndexed { i, n ->
                if (n == 0) return@forEachIndexed
                val height = (bottom - top) * n / max
                val left = slot * i + (slot - barWidth) / 2
                val path = Path().apply {
                    addRoundRect(RoundRect(left, bottom - height, left + barWidth, bottom, topLeftCornerRadius = radius, topRightCornerRadius = radius))
                }
                drawPath(path, bar)
                if (i == busiest) {
                    val text = measurer.measure("$n", labelStyle)
                    drawText(text, topLeft = Offset(left + barWidth / 2 - text.size.width / 2f, bottom - height - text.size.height - 2.dp.toPx()))
                }
            }
            drawLine(baseline, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 12) {
                Text(
                    monthName(i).take(1),
                    style = labelStyle.copy(color = if (i == selected) MaterialTheme.colorScheme.onSurface else labelColor),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A ranked list: "1. José Saramago · 2". */
@Composable
private fun TopList(title: String, names: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (names.isEmpty()) Text("–", style = MaterialTheme.typography.bodySmall)
        names.forEachIndexed { i, (name, count) ->
            Text(
                "${i + 1}. $name · $count",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
