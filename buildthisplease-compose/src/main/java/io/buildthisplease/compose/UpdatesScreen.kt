package io.buildthisplease.compose

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.buildthisplease.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A host-navigable completion inbox. Email is optional and confirmed independently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildThisPleaseUpdates(client: BuildThisPleaseClientProtocol, onBack: () -> Unit, onOpenTicket: (Ticket) -> Unit, modifier: Modifier = Modifier) {
    var updates by remember(client) { mutableStateOf(emptyList<CompletionNotification>()) }
    var preferences by remember(client) { mutableStateOf(NotificationPreferences()) }
    var email by remember(client) { mutableStateOf("") }
    var cursor by remember(client) { mutableStateOf<String?>(null) }
    var loading by remember(client) { mutableStateOf(false) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    var notice by remember(client) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val operations = remember(client) { Mutex() }
    suspend fun performOperation(action: suspend () -> Unit) {
        operations.withLock {
            loading = true
            try { action(); error = null }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
            finally { loading = false }
        }
    }
    suspend fun load(next: String? = null) {
        performOperation {
            val page = client.notifications(next)
            preferences = client.notificationPreferences()
            email = preferences.email.orEmpty()
            updates = if (next == null) page.items else (updates + page.items).distinctBy { it.id }
            cursor = page.nextCursor
        }
    }
    fun saveLocal(enabled: Boolean) {
        scope.launch {
            performOperation { preferences = client.updateNotificationPreferences(localNotificationsEnabled = enabled) }
        }
    }
    val deniedMessage = stringResource(R.string.btp_notifications_denied)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) saveLocal(true) else error = deniedMessage
    }
    val savedMessage = stringResource(R.string.btp_preferences_saved)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { load() } }
    BackHandler(onBack = onBack)
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.btp_updates)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.btp_back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (updates.isEmpty() && !loading) item { Text(stringResource(R.string.btp_no_updates)); Text(stringResource(R.string.btp_updates_empty_detail), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(updates, key = { it.id }) { update ->
                ListItem(
                    headlineContent = { Text(update.title, fontWeight = if (update.readAt == null) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = { Column {
                        update.appVersion?.let { Text(stringResource(R.string.btp_available_version, it)) }
                        update.implementationNote?.let { Text(it) }
                        if (update.readAt == null) Text(stringResource(R.string.btp_unread), color = MaterialTheme.colorScheme.primary)
                    } },
                    modifier = Modifier.clickable(enabled = !loading) {
                        scope.launch {
                            performOperation {
                                val ticket = client.ticket(update.ticketId)
                                client.markNotificationsRead(listOf(update.id))
                                updates = updates.map { if (it.id == update.id) it.copy(readAt = "read") else it }
                                onOpenTicket(ticket)
                            }
                        }
                    },
                )
            }
            cursor?.let { next -> item { TextButton(onClick = { scope.launch { load(next) } }, enabled = !loading) { Text(stringResource(R.string.btp_load_more)) } } }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.btp_notification_preferences), style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(value = email, onValueChange = { email = it }, enabled = !loading, label = { Text(stringResource(R.string.btp_email_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Button(onClick = {
                val destination = email.trim()
                scope.launch {
                    performOperation { preferences = client.updateNotificationPreferences(email = destination, emailEnabled = true); notice = savedMessage }
                }
            }, enabled = !loading && email.isNotBlank() && isValidRequestEmail(email)) { Text(stringResource(R.string.btp_enable_completion_email)) } }
            if (preferences.verificationPending) item { Text(stringResource(R.string.btp_verify_email_detail)) }
            if (preferences.emailEnabled || preferences.verificationPending) item { TextButton(onClick = {
                scope.launch {
                    performOperation { preferences = client.updateNotificationPreferences(emailEnabled = false); notice = savedMessage }
                }
            }, enabled = !loading) { Text(stringResource(R.string.btp_stop_completion_email)) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.btp_local_notifications), modifier = Modifier.weight(1f))
                Switch(checked = preferences.localNotificationsEnabled, enabled = !loading, onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33) permissions.launch(Manifest.permission.POST_NOTIFICATIONS) else saveLocal(enabled)
                })
            }; Text(stringResource(R.string.btp_local_notifications_detail), style = MaterialTheme.typography.bodySmall) }
            notice?.let { item { Text(it) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { load() } }) { Text(stringResource(R.string.btp_try_again)) } } }
            item { TextButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text(stringResource(R.string.btp_refresh_updates)) } }
        }
    }
}

@Composable
internal fun RequestFollowButton(client: BuildThisPleaseClientProtocol, ticketId: String, hasVoted: Boolean?) {
    var following by remember(client, ticketId) { mutableStateOf<Boolean?>(null) }
    var pendingOperations by remember(client, ticketId) { mutableIntStateOf(0) }
    var error by remember(client, ticketId) { mutableStateOf<String?>(null) }
    var refresh by remember(client, ticketId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // A vote response can refresh follow state while a manual mute is pending.
    val operations = remember(client, ticketId) { Mutex() }
    LaunchedEffect(client, ticketId, hasVoted, refresh) {
        pendingOperations++
        try {
            operations.withLock {
                try { following = client.following(ticketId); error = null }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; following = null; error = failure.message }
            }
        } finally { pendingOperations-- }
    }
    Column {
        TextButton(enabled = pendingOperations == 0 && following != null, onClick = {
            val next = following != true
            pendingOperations++
            scope.launch {
                try {
                    operations.withLock {
                        try { client.setFollowing(ticketId, next); following = next; error = null }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                    }
                } finally { pendingOperations-- }
            }
        }) { Text(stringResource(if (following == true) R.string.btp_stop_updates else R.string.btp_follow_request)) }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { refresh++ }, enabled = pendingOperations == 0) { Text(stringResource(R.string.btp_try_again)) }
        }
    }
}
