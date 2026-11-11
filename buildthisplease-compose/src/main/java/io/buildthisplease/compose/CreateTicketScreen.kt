package io.buildthisplease.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.buildthisplease.core.newIdempotencyKey

internal const val REQUEST_TITLE_LIMIT = 100
internal const val REQUEST_DESCRIPTION_LIMIT = 5_000
internal const val REQUEST_EMAIL_LIMIT = 320
internal const val MESSAGE_LIMIT = 5_000

private val workerEmailPattern = Regex(
    "^(?!\\.)(?!.*\\.\\.)([A-Za-z0-9_'+\\-.]*[A-Za-z0-9_+\\-])@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$",
)

internal fun isValidRequestEmail(value: String): Boolean {
    val clean = value.trim()
    return clean.isEmpty() || (clean.length <= REQUEST_EMAIL_LIMIT && workerEmailPattern.matches(clean))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateTicketScreen(
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onClearError: () -> Unit,
    onCreate: (title: String, description: String, email: String?, emailUpdates: Boolean, idempotencyKey: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var emailUpdates by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    val idempotencyKey by rememberSaveable { mutableStateOf(newIdempotencyKey()) }
    val titleFocus = remember { FocusRequester() }
    val cleanTitle = title.trim()
    val cleanDescription = description.trim()
    val cleanEmail = email.trim()
    val valid = cleanTitle.isNotEmpty() && cleanTitle.length <= REQUEST_TITLE_LIMIT &&
        cleanDescription.isNotEmpty() && cleanDescription.length <= REQUEST_DESCRIPTION_LIMIT &&
        isValidRequestEmail(cleanEmail)

    BackHandler(enabled = !isSaving, onBack = onDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btp_new_request)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Default.Close, stringResource(R.string.btp_cancel))
                    }
                },
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.imePadding(), tonalElevation = 3.dp) {
                Button(
                    onClick = { onCreate(cleanTitle, cleanDescription, cleanEmail.ifEmpty { null }, emailUpdates, idempotencyKey) },
                    enabled = valid && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.btp_submit))
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                RequestField(
                    value = title,
                    onValueChange = { title = it; onClearError() },
                    label = stringResource(R.string.btp_title),
                    limit = REQUEST_TITLE_LIMIT,
                    modifier = Modifier.focusRequester(titleFocus),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    minHeight = 52.dp,
                    maxLines = 3,
                )
                RequestField(
                    value = description,
                    onValueChange = { description = it; onClearError() },
                    label = stringResource(R.string.btp_description),
                    limit = REQUEST_DESCRIPTION_LIMIT,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    minHeight = 200.dp,
                    maxLines = Int.MAX_VALUE,
                )
                EmailField(
                    value = email,
                    onValueChange = { email = it; if (it.isBlank() || !isValidRequestEmail(it)) emailUpdates = false; onClearError() },
                    isValid = isValidRequestEmail(email),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = emailUpdates, onCheckedChange = { emailUpdates = it }, enabled = cleanEmail.isNotEmpty() && isValidRequestEmail(email))
                    Text(stringResource(R.string.btp_email_when_ships))
                }
                if (emailUpdates) Text(stringResource(R.string.btp_confirm_email_setup), style = MaterialTheme.typography.bodySmall)
                error?.let { FormError(it) }
            }
        }
    }
    LaunchedEffect(Unit) { titleFocus.requestFocus() }
}

@Composable
private fun RequestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    limit: Int,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    minHeight: Dp,
    maxLines: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.btp_character_count, value.length, limit),
                style = MaterialTheme.typography.bodySmall,
                color = if (value.length > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTextSurface(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            minHeight = minHeight,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, isValid: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            stringResource(R.string.btp_email_optional),
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTextSurface(
            value = value,
            onValueChange = onValueChange,
            minHeight = 52.dp,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            ),
        )
        if (!isValid) {
            Text(
                stringResource(R.string.btp_invalid_email),
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FilledTextSurface(
    value: String,
    onValueChange: (String) -> Unit,
    minHeight: Dp,
    maxLines: Int,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        color = MaterialTheme.colorScheme.buildThisPleaseSectionContainer,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(minHeight).padding(16.dp),
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(LocalBuildThisPleaseTheme.current.accent),
            keyboardOptions = keyboardOptions,
            maxLines = maxLines,
        )
    }
}

@Composable
internal fun FormError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
