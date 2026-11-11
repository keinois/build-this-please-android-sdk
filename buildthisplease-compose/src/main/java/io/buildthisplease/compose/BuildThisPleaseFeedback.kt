package io.buildthisplease.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.buildthisplease.compose.R
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import io.buildthisplease.core.newIdempotencyKey
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun BuildThisPleaseFeedback(
    client: BuildThisPleaseClientProtocol,
    modifier: Modifier = Modifier,
    theme: BuildThisPleaseTheme = BuildThisPleaseTheme(),
    onBack: (() -> Unit)? = null,
    onOpenRequest: ((ticketId: String) -> Unit)? = null,
    onCreateRequest: (() -> Unit)? = null,
    stateKey: String = "default",
    translationProvider: BuildThisPleaseTranslationProvider? = null,
) {
    val model = feedbackViewModel(client, stateKey)
    val state by model.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable(client.identityScopeKey) { mutableStateOf(false) }
    LaunchedEffect(model, creating) { if (creating) model.closeTicket() }
    BuildThisPleaseMaterialTheme(accent = theme.accent) {
        FeedbackTranslationScope(translationProvider, client, enabled = !creating) {
            CompositionLocalProvider(LocalBuildThisPleaseTheme provides theme) {
                when {
                    creating -> CreateTicketScreen(
                        isSaving = state.isActing,
                        error = state.error,
                        onDismiss = { model.clearError(); creating = false },
                        onClearError = model::clearError,
                        onCreate = { title, description, email, emailUpdates, idempotencyKey ->
                            model.create(title, description, email, idempotencyKey, emailUpdates) { success ->
                                if (success) creating = false
                            }
                        },
                    )
                    state.selectedTicket != null -> TicketDetail(model, state, modifier, onBack = model::closeTicket)
                    else -> TicketBoard(
                        client = client,
                        state = state,
                        modifier = modifier,
                        onBack = onBack,
                        onCreate = { model.closeTicket(); onCreateRequest?.invoke() ?: run { creating = true } },
                        onRefresh = model::refreshCurrentSection,
                        onResume = model::refreshIfStale,
                        onSelectSection = model::selectSection,
                        onOpenUpdates = model::closeTicket,
                        onOpen = { ticket ->
                            if (onOpenRequest != null) { model.closeTicket(); onOpenRequest(ticket.id) }
                            else model.open(ticket)
                        },
                        onVote = model::toggleVote,
                    )
                }
            }
        }
    }
}

/** Board-only destination for host apps that own their Navigation Compose graph. */
@Composable
fun BuildThisPleaseFeedbackList(
    client: BuildThisPleaseClientProtocol,
    onOpenTicket: (ticketId: String) -> Unit,
    onCreateRequest: () -> Unit,
    modifier: Modifier = Modifier,
    theme: BuildThisPleaseTheme = BuildThisPleaseTheme(),
    onBack: (() -> Unit)? = null,
    stateKey: String = "default",
    translationProvider: BuildThisPleaseTranslationProvider? = null,
) {
    val model = feedbackViewModel(client, stateKey)
    val state by model.state.collectAsStateWithLifecycle()
    BuildThisPleaseMaterialTheme(accent = theme.accent) {
        FeedbackTranslationScope(translationProvider, client) {
            CompositionLocalProvider(LocalBuildThisPleaseTheme provides theme) {
                TicketBoard(
                    client = client,
                    state = state,
                    modifier = modifier,
                    onBack = onBack,
                    onCreate = { model.closeTicket(); onCreateRequest() },
                    onRefresh = model::refreshCurrentSection,
                    onResume = model::refreshIfStale,
                    onSelectSection = model::selectSection,
                    onOpenUpdates = model::closeTicket,
                    onOpen = { model.closeTicket(); onOpenTicket(it.id) },
                    onVote = model::toggleVote,
                )
            }
        }
    }
}

/** Detail destination for host-owned navigation. Pass the same [stateKey] used by the list. */
@Composable
fun BuildThisPleaseTicketDetail(
    client: BuildThisPleaseClientProtocol,
    ticketId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    theme: BuildThisPleaseTheme = BuildThisPleaseTheme(),
    stateKey: String = "default",
    translationProvider: BuildThisPleaseTranslationProvider? = null,
) {
    val model = feedbackViewModel(client, stateKey)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(ticketId) { model.open(ticketId) }
    BuildThisPleaseMaterialTheme(accent = theme.accent) {
        FeedbackTranslationScope(translationProvider, client) {
            CompositionLocalProvider(LocalBuildThisPleaseTheme provides theme) {
                val selected = state.selectedTicket
                if (selected?.id == ticketId) {
                    TicketDetail(model, state, modifier) { model.closeTicket(); onBack() }
                } else {
                    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.error != null) ErrorState(state.error) { model.open(ticketId) }
                        else CircularProgressIndicator()
                    }
                    BackHandler { model.closeTicket(); onBack() }
                }
            }
        }
    }
}

/** Create-request destination for host-owned navigation. */
@Composable
fun BuildThisPleaseCreateRequest(
    client: BuildThisPleaseClientProtocol,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    theme: BuildThisPleaseTheme = BuildThisPleaseTheme(),
    stateKey: String = "default",
) {
    val model = feedbackViewModel(client, stateKey)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.closeTicket() }
    BuildThisPleaseMaterialTheme(accent = theme.accent) {
        CompositionLocalProvider(LocalBuildThisPleaseTheme provides theme) {
            CreateTicketScreen(
                isSaving = state.isActing,
                error = state.error,
                onDismiss = { model.clearError(); onBack() },
                onClearError = model::clearError,
                onCreate = { title, description, email, emailUpdates, idempotencyKey ->
                    model.create(title, description, email, idempotencyKey, emailUpdates) { if (it) onCreated() }
                },
            )
        }
    }
}

@Composable
private fun feedbackViewModel(client: BuildThisPleaseClientProtocol, stateKey: String): FeedbackViewModel =
    viewModel(key = "BuildThisPlease:${client.identityScopeKey}:$stateKey") {
        FeedbackViewModel(client, createSavedStateHandle())
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketBoard(
    client: BuildThisPleaseClientProtocol,
    state: FeedbackUiState,
    modifier: Modifier,
    onBack: (() -> Unit)?,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onResume: () -> Unit,
    onSelectSection: (FeedbackSection) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpen: (Ticket) -> Unit,
    onVote: (Ticket) -> Unit,
) {
    var showingUpdates by rememberSaveable(client.identityScopeKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unreadController = remember(client, client.identityScopeKey) { UnreadCountController(client, scope) }
    val unread by unreadController.count.collectAsStateWithLifecycle()
    LifecycleResumeEffect(unreadController) {
        onResume()
        if (!showingUpdates) unreadController.refresh()
        onPauseOrDispose { unreadController.invalidate() }
    }
    fun openUpdates() {
        onOpenUpdates()
        unreadController.invalidate()
        showingUpdates = true
    }
    fun closeUpdates() {
        showingUpdates = false
        unreadController.refresh()
    }
    if (showingUpdates) {
        BuildThisPleaseUpdates(client = client, onBack = ::closeUpdates, onOpenTicket = { ticket -> closeUpdates(); onOpen(ticket) })
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btp_feature_requests), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.btp_back)) } },
                actions = {
                    TextButton(onClick = ::openUpdates) { Text(if (unread > 0) stringResource(R.string.btp_updates_count, unread) else stringResource(R.string.btp_updates)) }
                    FilledIconButton(
                        onClick = onCreate,
                        // TopAppBar supplies 4 dp and the standard button has 4 dp of
                        // touch-target breathing room; 8 dp more aligns its 40 dp circle
                        // with the list's 16 dp content edge.
                        modifier = Modifier.padding(end = 8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = LocalBuildThisPleaseTheme.current.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.btp_new_request))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val usesAccessibilityText = LocalDensity.current.fontScale >= 1.3f
            val sections = buildList {
                add(FeedbackSection.REQUESTS)
                if (state.mine.isNotEmpty()) add(FeedbackSection.MINE)
                add(FeedbackSection.IMPLEMENTED)
            }
            if (maxWidth >= 840.dp && !usesAccessibilityText) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(modifier = Modifier.width(230.dp)) {
                        Text(
                            stringResource(R.string.btp_feature_requests),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        sections.forEach { section ->
                            NavigationRailItem(
                                selected = state.section == section,
                                onClick = { onSelectSection(section) },
                                icon = { Icon(section.icon(), contentDescription = null) },
                                label = { Text(section.labelWithCount(state)) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                    FeedbackContent(
                        state,
                        modifier = Modifier.weight(1f),
                        onRetry = onRefresh,
                        onCreate = onCreate,
                        onOpen = onOpen,
                        onVote = onVote,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (usesAccessibilityText) {
                        AccessibleSectionMenu(state, sections, onSelectSection)
                    } else {
                        PrimaryScrollableTabRow(selectedTabIndex = sections.indexOf(state.section).coerceAtLeast(0), edgePadding = 12.dp) {
                            sections.forEach { section ->
                                Tab(
                                    selected = state.section == section,
                                    onClick = { onSelectSection(section) },
                                    text = { Text(section.labelWithCount(state)) },
                                    selectedContentColor = LocalBuildThisPleaseTheme.current.accent,
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    FeedbackContent(
                        state,
                        modifier = Modifier.weight(1f),
                        onRetry = onRefresh,
                        onCreate = onCreate,
                        onOpen = onOpen,
                        onVote = onVote,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessibleSectionMenu(
    state: FeedbackUiState,
    sections: List<FeedbackSection>,
    onSelectSection: (FeedbackSection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(state.section.labelWithCount(state), Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sections.forEach { section ->
                DropdownMenuItem(
                    text = { Text(section.labelWithCount(state)) },
                    onClick = { expanded = false; onSelectSection(section) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackContent(
    state: FeedbackUiState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (Ticket) -> Unit,
    onVote: (Ticket) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.isCurrentSectionRefreshing,
        onRefresh = onRetry,
        modifier = modifier.fillMaxSize(),
    ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.isCurrentSectionInitialLoading -> TicketListSkeleton()
            state.error != null && state.currentTickets.isEmpty() && state.section !in state.loadedSections -> ErrorState(state.error, onRetry)
            state.currentTickets.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.error != null) Box(Modifier.fillMaxWidth().padding(16.dp)) { ErrorBanner(state.error, onRetry) }
                EmptySection(state.section, onCreate)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error != null) item { ErrorBanner(state.error, onRetry) }
                items(state.currentTickets, key = Ticket::id) { ticket ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TicketCard(
                            ticket,
                            implemented = state.section == FeedbackSection.IMPLEMENTED,
                            isVoting = ticket.id in state.votingTicketIds,
                            onOpen = { onOpen(ticket) },
                            onVote = { onVote(ticket) },
                        )
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
    }
}

@Composable
private fun TicketListSkeleton() {
    val loadingLabel = stringResource(R.string.btp_loading_requests)
    LazyColumn(
        modifier = Modifier.fillMaxSize().clearAndSetSemantics {
            contentDescription = loadingLabel
            liveRegion = LiveRegionMode.Polite
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(6) { index ->
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.buildThisPleaseSectionContainer),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {}
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(if (index % 2 == 0) 0.62f else 0.78f).height(18.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            shape = MaterialTheme.shapes.small,
                        ) {}
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.9f).height(14.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            shape = MaterialTheme.shapes.small,
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: Ticket, implemented: Boolean, isVoting: Boolean, onOpen: () -> Unit, onVote: () -> Unit) {
    val translation = rememberFeedbackTranslation(ticket.translationFields(), requireVisibility = true)
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).then(translation.visibilityModifier).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.buildThisPleaseSectionContainer),
        shape = RoundedCornerShape(23.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                implemented -> Box(Modifier.width(58.dp).heightIn(min = 92.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, stringResource(R.string.btp_implemented), tint = Color(0xFF2E7D32), modifier = Modifier.size(24.dp))
                }
                ticket.canVote -> VoteButton(ticket, isVoting, onVote)
                else -> Box(Modifier.width(58.dp).heightIn(min = 92.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f).padding(top = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(translation.text("title", ticket.title), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!implemented) StatusLabel(ticket.status)
                }
                Text(ticket.implementationNote?.let { translation.text("note", it) } ?: translation.text("description", ticket.description), maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FeedbackTranslationDisclosure(translation)
            }
        }
    }
}

@Composable
private fun VoteButton(ticket: Ticket, isVoting: Boolean, onVote: () -> Unit) {
    val selected = ticket.hasVoted == true
    val voteStateDescription = if (ticket.voteLocked) stringResource(R.string.btp_author_vote_permanent) else if (selected) stringResource(R.string.btp_remove_vote) else stringResource(R.string.btp_vote_for_request)
    Box(
        modifier = Modifier
            .width(58.dp)
            .heightIn(min = 92.dp)
            .clickable(enabled = !isVoting && !ticket.voteLocked, role = Role.Button, onClick = onVote)
            .semantics {
            role = Role.Button
            stateDescription = voteStateDescription
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isVoting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(
                Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (selected) LocalBuildThisPleaseTheme.current.voteHighlight else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text((ticket.voteCount ?: 0).toString(), fontWeight = FontWeight.Bold, color = if (selected) LocalBuildThisPleaseTheme.current.voteHighlight else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketDetail(model: FeedbackViewModel, state: FeedbackUiState, modifier: Modifier, onBack: () -> Unit) {
    val ticket = requireNotNull(state.selectedTicket)
    val translation = rememberFeedbackTranslation(ticket.translationFields())
    var reply by remember(ticket.id) { mutableStateOf("") }
    var replyIdempotencyKey by remember(ticket.id) { mutableStateOf(newIdempotencyKey()) }
    var editing by remember { mutableStateOf<Comment?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.comments.size) {
        if (state.comments.isNotEmpty()) {
            // Let the list compose its bottom sentinel before targeting it.
            withFrameNanos { }
            val bottomIndex = 1 + (if (state.isLoading) 1 else 0) + 1 + (if (state.error != null) 1 else 0)
            listState.animateScrollToItem(bottomIndex)
        }
    }
                BackHandler { model.closeTicket(); onBack() }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(translation.text("title", ticket.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.btp_back)) } }, actions = { RequestFollowButton(model.client, ticket.id, ticket.hasVoted) }) },
        // The composer owns the navigation-bar inset so its surface can paint behind it.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { model.refreshTicket(ticket) },
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "summary") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TicketDetailSummary(
                                ticket = ticket,
                                isVoting = ticket.id in state.votingTicketIds,
                                onVote = { model.toggleVote(ticket) },
                                translation = translation,
                            )
                        }
                    }
                    if (state.isLoading) item(key = "loading") {
                        Box(
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    if (state.comments.isNotEmpty()) item(key = "conversation") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ConversationCard(state.comments, onEdit = {
                                model.clearError()
                                editing = it
                            })
                        }
                    }
                    if (state.error != null) item(key = "error") {
                        ErrorBanner(state.error) { model.refreshTicket(ticket) }
                    }
                    item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
                }
            }
            if (ticket.commentsLocked) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text(stringResource(R.string.btp_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                ReplyComposer(
                    value = reply,
                    onValueChange = { reply = it },
                    isSending = state.isActing,
                    onSend = {
                        model.reply(reply.trim(), replyIdempotencyKey) { success ->
                            if (success) {
                                reply = ""
                                replyIdempotencyKey = newIdempotencyKey()
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        }
                    },
                )
            }
        }
    }
    editing?.let { comment ->
        EditCommentSheet(
            comment = comment,
            isSaving = state.isActing,
            error = state.error,
            onDismiss = { if (!state.isActing) editing = null },
            onSave = { body -> model.edit(comment, body) { if (it) editing = null } },
        )
    }
}

@Composable
private fun TicketDetailSummary(ticket: Ticket, isVoting: Boolean, onVote: () -> Unit, translation: TranslatedFeedbackGroup) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 700.dp),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.buildThisPleaseSectionContainer),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            when {
                ticket.canVote -> VoteButton(ticket, isVoting, onVote)
                ticket.status == TicketStatus.IMPLEMENTED -> Box(
                    Modifier.width(62.dp).heightIn(min = 88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(
                    start = if (ticket.canVote || ticket.status == TicketStatus.IMPLEMENTED) 0.dp else 14.dp,
                    top = 14.dp,
                    end = 14.dp,
                    bottom = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusLabel(ticket.status)
                Text(translation.text("description", ticket.description), style = MaterialTheme.typography.bodyLarge)
                ticket.implementationNote?.let { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                        Text(translation.text("note", note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FeedbackTranslationDisclosure(translation)
            }
        }
    }
}

@Composable
private fun ConversationCard(comments: List<Comment>, onEdit: (Comment) -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.btp_conversation),
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = RoundedCornerShape(23.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.buildThisPleaseSectionContainer),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                comments.forEach { comment -> CommentBubble(comment, onEdit = { onEdit(comment) }) }
            }
        }
    }
}

@Composable
private fun ReplyComposer(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && value.length <= MESSAGE_LIMIT && !isSending
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.buildThisPleaseComposerBackground,
        tonalElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(23.dp),
                color = MaterialTheme.colorScheme.buildThisPleaseComposerField,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= MESSAGE_LIMIT) onValueChange(it) },
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.Top)
                            .padding(vertical = 5.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 5,
                        decorationBox = { field ->
                            if (value.isEmpty()) Text(stringResource(R.string.btp_reply), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        },
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                    ) {
                        if (isSending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.ArrowUpward, stringResource(R.string.btp_send_reply), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentBubble(comment: Comment, onEdit: () -> Unit) {
    val translation = rememberFeedbackTranslation(
        if (comment.isHidden) emptyList() else listOf(FeedbackTranslationField("body", comment.body.orEmpty())),
        requireVisibility = true,
    )
    val mine = comment.isMine
    val editable = mine && !comment.isHidden
    val editLabel = stringResource(R.string.btp_edit_message)
    val author = when {
        comment.author == Comment.Author.ADMINISTRATOR -> stringResource(R.string.btp_admin)
        mine -> stringResource(R.string.btp_user)
        else -> stringResource(R.string.btp_other_user)
    }
    var showsActions by remember(comment.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        if (mine) Spacer(Modifier.width(52.dp))
        Column(
            modifier = Modifier.weight(1f).then(translation.visibilityModifier),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                author,
                modifier = Modifier.padding(horizontal = 17.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .then(
                            if (editable) Modifier
                                .pointerInput(comment.id) {
                                    detectTapGestures(onLongPress = { showsActions = true })
                                }
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(label = editLabel) { onEdit(); true },
                                    )
                                }
                            else Modifier,
                        ),
                    shape = RoundedCornerShape(17.dp),
                    color = when {
                        comment.isHidden -> MaterialTheme.colorScheme.buildThisPleaseConversationBubble
                        mine -> LocalBuildThisPleaseTheme.current.accent
                        else -> MaterialTheme.colorScheme.buildThisPleaseConversationBubble
                    },
                ) {
                    Text(
                        if (comment.isHidden) stringResource(R.string.btp_message_removed) else translation.text("body", comment.body.orEmpty()),
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        color = if (mine && !comment.isHidden) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontStyle = if (comment.isHidden) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    )
                }
                DropdownMenu(expanded = showsActions, onDismissRequest = { showsActions = false }) {
                    DropdownMenuItem(
                        text = { Text(editLabel) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { showsActions = false; onEdit() },
                    )
                }
            }
            Row(Modifier.padding(horizontal = 17.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(formatCommentDate(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (comment.isEdited) Text(stringResource(R.string.btp_edited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!comment.isApproved) Text(stringResource(R.string.btp_pending_approval), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FeedbackTranslationDisclosure(translation, Modifier.padding(horizontal = 17.dp))
        }
        if (!mine) Spacer(Modifier.width(52.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCommentSheet(
    comment: Comment,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var body by remember(comment.id) { mutableStateOf(comment.body.orEmpty()) }
    val editPaneTitle = stringResource(R.string.btp_edit_message)
    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = Modifier.semantics { paneTitle = editPaneTitle },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.btp_edit_message), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                minLines = 6,
                supportingText = { Text(stringResource(R.string.btp_character_count, body.length, MESSAGE_LIMIT)) },
                isError = body.length > MESSAGE_LIMIT,
            )
            error?.let { FormError(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.btp_cancel)) }
                Button(
                    onClick = { onSave(body.trim()) },
                    enabled = body.isNotBlank() && body.length <= MESSAGE_LIMIT && !isSaving,
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.btp_save))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun ErrorState(detail: String?, onRetry: () -> Unit) = Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.btp_error_title), fontWeight = FontWeight.Bold); if (!detail.isNullOrBlank()) Text(detail); Button(onClick = onRetry) { Text(stringResource(R.string.btp_try_again)) } }
@Composable private fun ErrorBanner(detail: String?, onRetry: () -> Unit) = Card(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(detail ?: stringResource(R.string.btp_error_title), Modifier.weight(1f)); IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, stringResource(R.string.btp_try_again)) } } }

@Composable
private fun EmptySection(section: FeedbackSection, onCreate: () -> Unit) {
    val title = when (section) {
        FeedbackSection.REQUESTS -> stringResource(R.string.btp_empty_requests)
        FeedbackSection.MINE -> stringResource(R.string.btp_empty_mine)
        FeedbackSection.IMPLEMENTED -> stringResource(R.string.btp_empty_implemented)
    }
    val detail = when (section) {
        FeedbackSection.REQUESTS -> stringResource(R.string.btp_empty_requests_detail)
        FeedbackSection.MINE -> stringResource(R.string.btp_empty_mine_detail)
        FeedbackSection.IMPLEMENTED -> stringResource(R.string.btp_empty_implemented_detail)
    }
    Column(
        modifier = Modifier.padding(32.dp).widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(section.icon(), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (section != FeedbackSection.IMPLEMENTED) Button(onClick = onCreate) { Text(stringResource(R.string.btp_new_request)) }
    }
}

@Composable
private fun StatusLabel(status: TicketStatus) {
    val color = status.color()
    Surface(color = color.copy(alpha = 0.16f), shape = MaterialTheme.shapes.extraLarge) {
        Text(
            status.title().uppercase(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FeedbackSection.labelWithCount(state: FeedbackUiState): String = when (this) {
    FeedbackSection.REQUESTS -> "${stringResource(R.string.btp_requests)} (${state.requests.size})"
    FeedbackSection.MINE -> "${stringResource(R.string.btp_mine)} (${state.mine.size})"
    FeedbackSection.IMPLEMENTED -> "${stringResource(R.string.btp_done)} (${state.implemented.size})"
}

private fun FeedbackSection.icon(): ImageVector = when (this) {
    FeedbackSection.REQUESTS -> Icons.Default.Inbox
    FeedbackSection.MINE -> Icons.Default.Person
    FeedbackSection.IMPLEMENTED -> Icons.Default.CheckCircle
}

@Composable
private fun TicketStatus.title() = when (this) {
    TicketStatus.PENDING -> stringResource(R.string.btp_status_pending)
    TicketStatus.IN_REVIEW -> stringResource(R.string.btp_status_in_review)
    TicketStatus.PLANNED -> stringResource(R.string.btp_status_planned)
    TicketStatus.IN_PROGRESS -> stringResource(R.string.btp_status_in_progress)
    TicketStatus.IMPLEMENTED -> stringResource(R.string.btp_status_implemented)
    TicketStatus.REJECTED -> stringResource(R.string.btp_status_rejected)
    TicketStatus.MERGED -> stringResource(R.string.btp_status_merged)
    TicketStatus.ARCHIVED -> stringResource(R.string.btp_status_archived)
}

@Composable
private fun TicketStatus.color(): Color = when (this) {
    TicketStatus.PENDING -> Color(0xFFE17A00)
    TicketStatus.IN_REVIEW -> Color(0xFF00838F)
    TicketStatus.PLANNED -> Color(0xFF7B1FA2)
    TicketStatus.IN_PROGRESS -> Color(0xFF1565C0)
    TicketStatus.IMPLEMENTED -> Color(0xFF2E7D32)
    TicketStatus.REJECTED -> MaterialTheme.colorScheme.error
    TicketStatus.MERGED, TicketStatus.ARCHIVED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatCommentDate(value: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val date = runCatching { parser.parse(value) }.getOrNull() ?: return value
    return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
}
