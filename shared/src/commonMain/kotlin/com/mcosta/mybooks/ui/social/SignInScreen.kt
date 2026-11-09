package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    /** Shown above the form ("Sign in again to sync"); null for none. */
    notice: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.step) { if (state.step == SignInStep.DONE) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MyBooks community") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
            when (state.step) {
                SignInStep.LOADING -> {
                    if (state.error == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Button(onClick = viewModel::retry, enabled = !state.busy) { Text("Retry") }
                    }
                }
                SignInStep.EMAIL -> {
                    Text(
                        "Join the community to find friends and follow what they read. Your library stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::setEmail,
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::sendCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Email me a code")
                    }
                }
                SignInStep.CODE -> {
                    Text("Enter the 6-digit code we sent to ${state.email}.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = viewModel::setCode,
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::verify, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                    TextButton(onClick = viewModel::resend, enabled = !state.busy) { Text("Send a new code") }
                }
                SignInStep.USERNAME, SignInStep.DONE -> {
                    Text("Choose how other readers will see you.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        label = { Text("Username") },
                        prefix = { Text("@") },
                        singleLine = true,
                        isError = state.usernameError != null || state.usernameAvailable == false,
                        supportingText = {
                            Text(
                                state.usernameError
                                    ?: when (state.usernameAvailable) {
                                        true -> "Available"
                                        false -> "That username is taken"
                                        null -> "3–20 characters: a–z, 0–9, _ and ."
                                    }
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = viewModel::setDisplayName,
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::createProfile, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Create profile")
                    }
                }
            }
            state.info?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
