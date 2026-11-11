package io.buildthisplease.sample

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.buildthisplease.compose.BuildThisPleaseFeedback
import io.buildthisplease.compose.BuildThisPleaseMaterialTheme
import io.buildthisplease.core.BuildThisPleaseClient
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.BuildThisPleaseConfiguration
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.MockBuildThisPleaseScenario
import io.buildthisplease.core.SubscriptionStatus
import kotlinx.coroutines.CancellationException

private enum class ExampleMode { MOCK, STAGING, PRODUCTION }
private enum class SampleDestination { MENU, FEEDBACK, CONTROLS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SampleApp() }
    }
}

@Composable
private fun SampleApp() {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(if (BuildConfig.DEBUG) ExampleMode.MOCK else ExampleMode.PRODUCTION) }
    var scenario by rememberSaveable { mutableStateOf(MockBuildThisPleaseScenario.NORMAL) }
    var subscription by rememberSaveable { mutableStateOf(SubscriptionStatus.TRIAL) }
    var forceDarkMode by rememberSaveable { mutableStateOf(false) }
    var resetGeneration by rememberSaveable { mutableIntStateOf(0) }
    var destination by rememberSaveable { mutableStateOf(SampleDestination.MENU) }
    val client = remember(mode, scenario, subscription, resetGeneration) {
        sampleClient(context.applicationContext, mode, scenario, subscription)
    }
    // The host starts preloading while its own menu is visible. This effect owns cancellation;
    // a real host should retain the account client in its application/account container.
    LaunchedEffect(client) {
        try { client.preloadRequests() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure }
    }

    BuildThisPleaseMaterialTheme(darkTheme = forceDarkMode || isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (destination) {
                SampleDestination.FEEDBACK -> {
                    BackHandler { destination = SampleDestination.MENU }
                    BuildThisPleaseFeedback(
                        client = client,
                        stateKey = "sample-$mode-$scenario-$subscription-$resetGeneration",
                        onBack = { destination = SampleDestination.MENU },
                    )
                }
                SampleDestination.CONTROLS -> DeveloperControls(
                    mode, scenario, subscription, forceDarkMode,
                    onMode = { mode = it },
                    onScenario = { scenario = it },
                    onSubscription = { subscription = it },
                    onForceDarkMode = { forceDarkMode = it },
                    onReset = { resetGeneration++ },
                    onBack = { destination = SampleDestination.MENU },
                )
                SampleDestination.MENU -> ExampleMenu(
                    mode, subscription,
                    onOpenFeedback = { destination = SampleDestination.FEEDBACK },
                    onOpenControls = { destination = SampleDestination.CONTROLS },
                )
            }
        }
    }
}

private fun sampleClient(
    context: Context,
    mode: ExampleMode,
    scenario: MockBuildThisPleaseScenario,
    subscription: SubscriptionStatus,
): BuildThisPleaseClientProtocol {
    if (mode == ExampleMode.MOCK) return MockBuildThisPleaseClient(scenario = scenario, initialSubscriptionStatus = subscription)
    val baseUrl = if (mode == ExampleMode.PRODUCTION) BuildConfig.BTP_PRODUCTION_BASE_URL else BuildConfig.BTP_STAGING_BASE_URL
    val projectKey = if (mode == ExampleMode.PRODUCTION) BuildConfig.BTP_PRODUCTION_PROJECT_KEY else BuildConfig.BTP_STAGING_PROJECT_KEY
    if (baseUrl.isBlank() || projectKey.isBlank()) {
        return MockBuildThisPleaseClient(scenario = MockBuildThisPleaseScenario.SERVER_ERROR, initialSubscriptionStatus = subscription)
    }
    return BuildThisPleaseClient(
        context = context,
        configuration = BuildThisPleaseConfiguration(
            baseUrl = baseUrl,
            projectKey = projectKey,
            environment = if (mode == ExampleMode.PRODUCTION) BuildThisPleaseConfiguration.Environment.PRODUCTION else BuildThisPleaseConfiguration.Environment.DEVELOPMENT,
            subscriptionStatus = subscription,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExampleMenu(
    mode: ExampleMode,
    subscription: SubscriptionStatus,
    onOpenFeedback: () -> Unit,
    onOpenControls: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sample_title)) },
                actions = {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = onOpenControls) { Icon(Icons.Default.Settings, stringResource(R.string.sample_developer_controls)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                    color = if (mode == ExampleMode.MOCK) Color(0xFFE17A00) else Color(0xFF1565C0),
                ) {}
                Text(
                    "${mode.name} / ${subscription.name}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider()
            SectionTitle(stringResource(R.string.sample_menu_heading))
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenFeedback),
                headlineContent = { Text(stringResource(io.buildthisplease.compose.R.string.btp_feature_requests)) },
                leadingContent = { Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            Text(
                stringResource(R.string.sample_explanation),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperControls(
    mode: ExampleMode,
    scenario: MockBuildThisPleaseScenario,
    subscription: SubscriptionStatus,
    forceDarkMode: Boolean,
    onMode: (ExampleMode) -> Unit,
    onScenario: (MockBuildThisPleaseScenario) -> Unit,
    onSubscription: (SubscriptionStatus) -> Unit,
    onForceDarkMode: (Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sample_developer_controls)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.sample_done)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionTitle(stringResource(R.string.sample_run_mode)) }
            item { ChoiceRow(stringResource(R.string.sample_environment), mode, ExampleMode.entries, onMode, ::enumLabel) }
            item { SectionTitle(stringResource(R.string.sample_subscription)) }
            item { ChoiceRow(stringResource(R.string.sample_reported_state), subscription, SubscriptionStatus.entries, onSubscription, ::enumLabel) }
            item { SectionTitle(stringResource(R.string.sample_mock_response)) }
            item { ChoiceRow(stringResource(R.string.sample_scenario), scenario, MockBuildThisPleaseScenario.entries, onScenario, ::enumLabel) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.sample_reset)) }, modifier = Modifier.clickable(onClick = onReset)) }
            item { SectionTitle(stringResource(R.string.sample_appearance)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sample_force_dark)) },
                    trailingContent = { Switch(checked = forceDarkMode, onCheckedChange = onForceDarkMode) },
                    modifier = Modifier.clickable { onForceDarkMode(!forceDarkMode) },
                )
            }
            item { SectionTitle(stringResource(R.string.sample_runtime)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.sample_package)) }, supportingContent = { Text(BuildConfig.APPLICATION_ID) }) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sample_integrity)) },
                    supportingContent = { Text(stringResource(if (mode == ExampleMode.MOCK) R.string.sample_mock_integrity else R.string.sample_play_integrity)) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    selected: T,
    options: List<T>,
    onSelected: (T) -> Unit,
    label: (T) -> String,
) {
    var choosing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected)) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable { choosing = true },
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelected(option); choosing = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = { onSelected(option); choosing = false })
                            Text(label(option), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.sample_cancel)) } },
        )
    }
}

private fun enumLabel(value: Enum<*>): String =
    value.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
