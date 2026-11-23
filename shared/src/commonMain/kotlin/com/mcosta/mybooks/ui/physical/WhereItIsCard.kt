package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.findShelf
import com.mcosta.mybooks.ui.detail.SectionCard
import com.mcosta.mybooks.ui.library.lentText

/** 1st, 2nd, 3rd, 4th, 11th, 21st… */
fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/** Where to go to find this book, and the way to put it there. A To buy book has no place. */
@Composable
fun WhereItIsCard(
    book: Book,
    bookcases: List<Bookcase>,
    booksOn: (String) -> List<Book>,
    onChoose: (shelfId: String) -> Unit,
    onTakeOff: () -> Unit,
    onOpenShelf: (String) -> Unit,
    onAddBookcase: () -> Unit,
) {
    if (book.status == ReadingStatus.WISHLIST) return
    var choosing by remember { mutableStateOf(false) }
    val place = bookcases.findShelf(book.shelfId)
    SectionCard("Where it is") {
        when {
            place != null -> {
                Text(
                    place.location,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable { onOpenShelf(place.shelfId) },
                )
                if (book.shelfPosition != null) {
                    val index = booksOn(place.shelfId).indexOfFirst { it.id == book.id }
                    if (index >= 0) Text("${ordinal(index + 1)} from the left", style = MaterialTheme.typography.bodyMedium)
                }
                book.lentTo?.let { Text(lentText(it, book.lentOn), style = MaterialTheme.typography.bodyMedium) }
                BookcaseDrawing(
                    place.bookcase, booksOn, Modifier.fillMaxWidth(),
                    shelfHeight = 32.dp, highlightShelf = place.shelfId, highlightBook = book.id,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosing = true }) { Text("Move") }
                    TextButton(onClick = onTakeOff) { Text("Take off the shelf") }
                }
            }
            bookcases.isEmpty() -> OutlinedButton(onClick = onAddBookcase) { Text("Add a bookcase") }
            else -> OutlinedButton(onClick = { choosing = true }) { Text("Put on a shelf") }
        }
    }
    if (choosing) {
        ShelfPickerDialog(
            bookcases, place?.shelfId,
            onPick = { choosing = false; onChoose(it) },
            onDismiss = { choosing = false },
        )
    }
}
