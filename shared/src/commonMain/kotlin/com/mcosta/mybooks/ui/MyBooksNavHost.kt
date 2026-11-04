package com.mcosta.mybooks.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.share.SharingState
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.data.releases.NOT_STATED
import com.mcosta.mybooks.data.releases.ReleaseFilter
import com.mcosta.mybooks.data.releases.ReleaseGenre
import com.mcosta.mybooks.ui.releases.ReleaseBookScreen
import com.mcosta.mybooks.ui.releases.ReleaseBookViewModel
import com.mcosta.mybooks.ui.releases.ReleaseGenreScreen
import com.mcosta.mybooks.ui.releases.ReleaseGenreViewModel
import com.mcosta.mybooks.ui.releases.ReleasesScreen
import com.mcosta.mybooks.ui.releases.ReleasesSearchScreen
import com.mcosta.mybooks.ui.releases.ReleasesSearchViewModel
import com.mcosta.mybooks.ui.releases.ReleasesViewModel
import com.mcosta.mybooks.ui.releases.ReleaseView
import io.ktor.http.encodeURLPathPart
import com.mcosta.mybooks.ui.social.AccountRoute
import com.mcosta.mybooks.ui.social.ActivityScreen
import com.mcosta.mybooks.ui.social.ActivityViewModel
import com.mcosta.mybooks.ui.social.FriendsScreen
import com.mcosta.mybooks.ui.social.FriendsViewModel
import com.mcosta.mybooks.ui.social.ProfileScreen
import com.mcosta.mybooks.ui.social.ProfileViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.ui.detail.BookDetailScreen
import com.mcosta.mybooks.ui.detail.BookDetailViewModel
import com.mcosta.mybooks.ui.detail.BookExtrasViewModel
import com.mcosta.mybooks.ui.detail.CatalogBookScreen
import com.mcosta.mybooks.ui.detail.CatalogBookViewModel
import com.mcosta.mybooks.ui.edit.EditBookScreen
import com.mcosta.mybooks.ui.edit.EditBookViewModel
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.celebrate.CelebrationDialog
import com.mcosta.mybooks.ui.celebrate.CelebrationViewModel
import com.mcosta.mybooks.ui.activity.ReadYearScreen
import com.mcosta.mybooks.ui.activity.ReadYearViewModel
import com.mcosta.mybooks.ui.activity.ReadingActivityScreen
import com.mcosta.mybooks.ui.activity.ReadingActivityViewModel
import com.mcosta.mybooks.ui.home.HomeScreen
import com.mcosta.mybooks.ui.home.HomeViewModel
import com.mcosta.mybooks.ui.home.shelfLabel
import com.mcosta.mybooks.ui.library.LibraryScreen
import com.mcosta.mybooks.ui.library.LibraryViewModel
import com.mcosta.mybooks.ui.settings.SettingsScreen
import com.mcosta.mybooks.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

object Routes {
    /** The home screen: Reading, your reading activity and the shelves. */
    const val LIBRARY = "library"
    /** A shelf's list: {section} is a [ReadingStatus] name or ALL; search=true focuses the search box. */
    const val SHELF = "shelf/{section}?search={search}"
    /** All books with the search box ready (the bottom bar's Search). */
    const val SEARCH = "search"
    const val READING_ACTIVITY = "reading-activity"
    /** A catalogue book (from "More by …"); {key} finds it in [com.mcosta.mybooks.AppContainer.catalogBooks]. */
    const val CATALOG_BOOK = "catalog/{key}"
    /** The books read in {year}, or "undated" for read books without a read date. */
    const val READ_YEAR = "reading-activity/{year}"
    const val ADD = "add?isbn={isbn}"
    const val EDIT = "edit/{id}"
    const val BOOK = "book/{id}"
    const val SETTINGS = "settings"
    const val FRIENDS = "friends?tab={tab}"
    const val ACTIVITY = "activity"
    const val RELEASES = "releases"
    const val RELEASE_GENRE = "releases/{month}/{genre}"
    const val RELEASE_BOOK = "releases/{month}/book/{id}"
    const val RELEASE_AUTHOR = "releases/{month}/author/{name}"
    const val RELEASE_PUBLISHER = "releases/{month}/publisher/{name}"
    const val RELEASE_SEARCH = "releases/search"

    /** The {month} of a release list covering every month. */
    const val ALL_MONTHS = "all"
    const val PROFILE = "profile/{id}"
    const val ACCOUNT = "account"

    fun shelf(section: ReadingStatus?, search: Boolean = false) = "shelf/${section?.name ?: ALL_SHELF}?search=$search"
    fun readYear(year: Int?) = "reading-activity/${year ?: UNDATED}"
    const val ALL_SHELF = "ALL"
    const val UNDATED = "undated"
    fun add(isbn: String? = null) = if (isbn == null) "add" else "add?isbn=$isbn"
    fun edit(id: Long) = "edit/$id"
    fun book(id: Long) = "book/$id"
    fun profile(userId: String) = "profile/$userId"
    fun friends(tab: Int = 0) = "friends?tab=$tab"
    fun releaseGenre(month: String, genre: String) = "releases/$month/$genre"
    fun releaseBook(month: String, id: String) = "releases/$month/book/$id"
    /** An author's or publisher's books; the name is URL-encoded (it may contain spaces, accents or slashes). */
    fun releaseName(month: String, view: ReleaseView, name: String) =
        "releases/$month/${if (view == ReleaseView.AUTHOR) "author" else "publisher"}/${name.encodeURLPathPart()}"
}

@Composable
fun MyBooksNavHost(container: AppContainer) {
    val social = container.social
    val sharing = container.sharingStatus?.state?.collectAsStateWithLifecycle()?.value ?: SharingState()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val services = LocalPlatformServices.current

    val celebrations = viewModel { CelebrationViewModel(container.repository, container.challenges, container.events) }
    val celebration by celebrations.celebration.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val tabs = bottomTabs(community = social != null)
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Routes.LIBRARY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        // Each screen handles the system bars; the bottom bar's space is taken off here.
        contentWindowInsets = WindowInsets(0),
    ) { bars ->
        NavHost(nav, startDestination = Routes.LIBRARY, modifier = Modifier.padding(bars).consumeWindowInsets(bars)) {
            composable(Routes.LIBRARY) {
                HomeScreen(
                    viewModel = viewModel { HomeViewModel(container.repository, container.challenges) },
                    snackbarHostState = snackbar,
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    onOpenShelf = { nav.navigate(Routes.shelf(it)) },
                    onOpenReadingActivity = { nav.navigate(Routes.READING_ACTIVITY) },
                    onAddManually = { nav.navigate(Routes.add()) },
                    onScan = {
                        scope.launch {
                            when (val outcome = services.scanner.scan()) {
                                is ScanOutcome.Isbn -> {
                                    val existing = container.repository.findByIsbn(outcome.isbn)
                                    nav.navigate(if (existing != null) Routes.book(existing.id) else Routes.add(outcome.isbn))
                                }
                                ScanOutcome.NotAnIsbn -> snackbar.showSnackbar("That barcode isn't a book ISBN")
                                is ScanOutcome.Failed -> snackbar.showSnackbar(outcome.message)
                                ScanOutcome.Cancelled -> Unit
                            }
                        }
                    },
                )
            }
            composable(
                Routes.SHELF,
                arguments = listOf(
                    navArgument("section") { type = NavType.StringType },
                    navArgument("search") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val section = ReadingStatus.entries.firstOrNull { it.name == entry.savedStateHandle.get<String>("section") }
                LibraryScreen(
                    viewModel = viewModel { LibraryViewModel(container.repository, container.preferences, section, container.applicationScope) },
                    title = section.shelfLabel(),
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    focusSearch = entry.savedStateHandle.get<Boolean>("search") == true,
                )
            }
            composable(Routes.SEARCH) {
                LibraryScreen(
                    viewModel = viewModel { LibraryViewModel(container.repository, container.preferences, section = null, appScope = container.applicationScope) },
                    title = "Search",
                    onBack = null,
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    focusSearch = true,
                )
            }
            composable(Routes.READING_ACTIVITY) {
                ReadingActivityScreen(
                    viewModel = viewModel { ReadingActivityViewModel(container.repository, container.challenges) },
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    onOpenYear = { nav.navigate(Routes.readYear(it)) },
                )
            }
            composable(Routes.READ_YEAR, arguments = listOf(navArgument("year") { type = NavType.StringType })) { entry ->
                val year = entry.savedStateHandle.get<String>("year")?.toIntOrNull()
                ReadYearScreen(
                    viewModel = viewModel { ReadYearViewModel(container.repository, year) },
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                )
            }
            composable(
                Routes.ADD,
                arguments = listOf(navArgument("isbn") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val isbn = entry.savedStateHandle.get<String>("isbn")
                EditBookScreen(
                    viewModel = viewModel { EditBookViewModel(container.repository, bookId = null, isbn = isbn) },
                    isNew = true,
                    onBack = { nav.popBackStack() },
                    onSaved = { id ->
                        nav.navigate(Routes.book(id)) { popUpTo(Routes.LIBRARY) }
                    },
                    showReview = sharing.hasProfile,
                )
            }
            composable(Routes.BOOK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.savedStateHandle.get<Long>("id")!!
                BookDetailScreen(
                    viewModel = viewModel { BookDetailViewModel(container.repository, id, container.applicationScope, container.authors) },
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.edit(id)) },
                    sharing = sharing,
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    extras = viewModel { BookExtrasViewModel(container.repository, id, social, container.authorBooks) },
                    onJoin = { nav.navigate(Routes.ACCOUNT) },
                    onOpenPerson = { nav.navigate(Routes.profile(it)) },
                    onOpenCatalogBook = { nav.navigate("catalog/${container.catalogBooks.put(it)}") },
                )
            }
            composable(Routes.CATALOG_BOOK, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                val key = entry.savedStateHandle.get<String>("key").orEmpty()
                CatalogBookScreen(
                    viewModel = viewModel { CatalogBookViewModel(container.repository, container.catalogBooks.get(key)) },
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                )
            }
            composable(Routes.RELEASES) {
                ReleasesScreen(
                    viewModel = viewModel { ReleasesViewModel(container.releases) },
                    onBack = null,
                    onOpenGenre = { month, genre -> nav.navigate(Routes.releaseGenre(month, genre.key)) },
                    onOpenName = { month, view, name -> nav.navigate(Routes.releaseName(month, view, name.key)) },
                    onSearch = { nav.navigate(Routes.RELEASE_SEARCH) },
                )
            }
            composable(
                Routes.RELEASE_GENRE,
                arguments = listOf(navArgument("month") { type = NavType.StringType }, navArgument("genre") { type = NavType.StringType }),
            ) { entry ->
                val month = entry.savedStateHandle.get<String>("month")!!.takeUnless { it == Routes.ALL_MONTHS }
                val genre = ReleaseGenre.of(entry.savedStateHandle.get<String>("genre")!!)
                ReleaseGenreScreen(
                    viewModel = viewModel { ReleaseGenreViewModel(container.releases, month, ReleaseFilter.Genre(genre), container.preferences) },
                    title = genre.label,
                    month = month,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { bookMonth, id -> nav.navigate(Routes.releaseBook(bookMonth, id)) },
                )
            }
            for ((route, view) in listOf(Routes.RELEASE_AUTHOR to ReleaseView.AUTHOR, Routes.RELEASE_PUBLISHER to ReleaseView.PUBLISHER)) {
                composable(
                    route,
                    arguments = listOf(navArgument("month") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType }),
                ) { entry ->
                    val month = entry.savedStateHandle.get<String>("month")!!.takeUnless { it == Routes.ALL_MONTHS }
                    val name = entry.savedStateHandle.get<String>("name")!!
                    val filter = if (view == ReleaseView.AUTHOR) ReleaseFilter.Author(name) else ReleaseFilter.Publisher(name)
                    val title = when {
                        name != NOT_STATED -> name
                        view == ReleaseView.AUTHOR -> "Author not stated"
                        else -> "Publisher not stated"
                    }
                    ReleaseGenreScreen(
                        viewModel = viewModel { ReleaseGenreViewModel(container.releases, month, filter, container.preferences) },
                        title = title,
                        month = month,
                        onBack = { nav.popBackStack() },
                        onOpenBook = { bookMonth, id -> nav.navigate(Routes.releaseBook(bookMonth, id)) },
                    )
                }
            }
            composable(Routes.RELEASE_SEARCH) {
                ReleasesSearchScreen(
                    viewModel = viewModel { ReleasesSearchViewModel(container.releases) },
                    onBack = { nav.popBackStack() },
                    onOpen = { view, key ->
                        nav.navigate(
                            if (view == ReleaseView.GENRE) Routes.releaseGenre(Routes.ALL_MONTHS, key) else Routes.releaseName(Routes.ALL_MONTHS, view, key),
                        )
                    },
                )
            }
            composable(
                Routes.RELEASE_BOOK,
                arguments = listOf(navArgument("month") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val month = entry.savedStateHandle.get<String>("month")!!
                val id = entry.savedStateHandle.get<String>("id")!!
                ReleaseBookScreen(
                    viewModel = viewModel { ReleaseBookViewModel(container.releases, container.repository, month, id) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                val session = social?.auth?.session?.collectAsStateWithLifecycle()?.value
                SettingsScreen(
                    viewModel = viewModel { SettingsViewModel(container.repository, services.documents, covers = container.coverFinder, appearance = container.appearance) },
                    onBack = null,
                    accountLabel = social?.let {
                        if (session is SessionState.SignedIn) "Your community account" else "Join the MyBooks community"
                    },
                    onOpenAccount = social?.let { { nav.navigate(Routes.ACCOUNT) } },
                )
            }
            if (social != null) {
                composable(Routes.FRIENDS, arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { entry ->
                    FriendsScreen(
                        viewModel = viewModel { FriendsViewModel(social) },
                        onBack = { nav.popBackStack() },
                        onSignIn = { nav.navigate(Routes.ACCOUNT) },
                        onOpenPerson = { nav.navigate(Routes.profile(it)) },
                        initialTab = entry.savedStateHandle.get<Int>("tab") ?: 0,
                    )
                }
                composable(Routes.ACTIVITY) {
                    ActivityScreen(
                        viewModel = viewModel { ActivityViewModel(social, container.repository) },
                        onBack = null,
                        onOpenFriends = { nav.navigate(Routes.friends()) },
                        onJoin = { nav.navigate(Routes.ACCOUNT) },
                        onFindPeople = { nav.navigate(Routes.friends(tab = 2)) },
                        onOpenPerson = { nav.navigate(Routes.profile(it)) },
                    )
                }
                composable(Routes.PROFILE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val userId = entry.savedStateHandle.get<String>("id")!!
                    ProfileScreen(
                        viewModel { ProfileViewModel(social, userId, container.repository) },
                        onBack = { nav.popBackStack() },
                        onOpenPerson = { nav.navigate(Routes.profile(it)) },
                    )
                }
                composable(Routes.ACCOUNT) {
                    AccountRoute(social, container.sharingStatus, onBack = { nav.popBackStack() })
                }
            }
            composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.savedStateHandle.get<Long>("id")!!
                EditBookScreen(
                    viewModel = viewModel { EditBookViewModel(container.repository, bookId = id, isbn = null) },
                    isNew = false,
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                    showReview = sharing.hasProfile,
                )
            }
        }
    }

    // Marking a book as Read, from any screen, is celebrated here.
    celebration?.let { c ->
        CelebrationDialog(
            c,
            onRate = celebrations::rate,
            onSetGoal = { celebrations.dismiss(); nav.navigate(Routes.READING_ACTIVITY) },
            onDismiss = celebrations::dismiss,
        )
    }
}

/** A main screen in the bottom bar. */
private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

/** Home, Search, New in Portugal, Community (when the community is set up) and Settings. */
private fun bottomTabs(community: Boolean) = listOfNotNull(
    BottomTab(Routes.LIBRARY, "Home", Icons.Filled.Home),
    BottomTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    BottomTab(Routes.RELEASES, "New books", Icons.Filled.NewReleases),
    BottomTab(Routes.ACTIVITY, "Community", Icons.Filled.Groups).takeIf { community },
    BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)
