package com.example.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AuthType
import com.example.domain.model.ConnectionTestState
import com.example.domain.model.GeneratedSshKey
import com.example.domain.model.SshKeyAlgorithm
import com.example.ui.components.HostKeyVerificationDialog
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    viewModel: ServerEditorViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val form = uiState.form
    val testState = uiState.testState
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPassword by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }
    var showManualKeyInput by remember { mutableStateOf(false) }
    var showGenerateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(form.savedSuccessfully) {
        if (form.savedSuccessfully) {
            onNavigateBack()
        }
    }

    val keyFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val result = readPrivateKeyFromUri(context, uri)
            if (result != null) {
                viewModel.onKeyImported(result.first, result.second)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.editor_key_import_error))
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (form.isEditing) R.string.editor_title_edit else R.string.editor_title_new,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("editor_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !form.isSaving,
                        modifier = Modifier.testTag("editor_top_save_button"),
                    ) {
                        Text(text = stringResource(R.string.editor_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                form.validationErrorRes?.let { errorRes ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(errorRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // Section 1: Connection
                EditorSectionCard(title = stringResource(R.string.editor_section_connection)) {
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.editor_name_label)) },
                        placeholder = { Text(stringResource(R.string.editor_name_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_name_input"),
                    )

                    OutlinedTextField(
                        value = form.host,
                        onValueChange = viewModel::onHostChange,
                        label = { Text(stringResource(R.string.editor_host_label)) },
                        placeholder = { Text(stringResource(R.string.editor_host_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_host_input"),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = form.sshPortText,
                            onValueChange = viewModel::onSshPortChange,
                            label = { Text(stringResource(R.string.editor_ssh_port_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("editor_ssh_port_input"),
                        )

                        OutlinedTextField(
                            value = form.openCodePortText,
                            onValueChange = viewModel::onOpenCodePortChange,
                            label = { Text(stringResource(R.string.editor_opencode_port_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("editor_opencode_port_input"),
                        )
                    }

                    Text(
                        text = stringResource(R.string.editor_opencode_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = form.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text(stringResource(R.string.editor_username_label)) },
                        placeholder = { Text(stringResource(R.string.editor_username_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_username_input"),
                    )
                }

                // Section 2: Authentication
                EditorSectionCard(title = stringResource(R.string.editor_section_auth)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = form.authType == AuthType.PRIVATE_KEY,
                            onClick = { viewModel.onAuthTypeChange(AuthType.PRIVATE_KEY) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("auth_type_key_button"),
                        ) {
                            Text(text = stringResource(R.string.servers_auth_key))
                        }
                        SegmentedButton(
                            selected = form.authType == AuthType.PASSWORD,
                            onClick = { viewModel.onAuthTypeChange(AuthType.PASSWORD) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Password,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("auth_type_password_button"),
                        ) {
                            Text(text = stringResource(R.string.servers_auth_password))
                        }
                    }

                    if (form.authType == AuthType.PASSWORD) {
                        OutlinedTextField(
                            value = form.passwordInput,
                            onValueChange = viewModel::onPasswordChange,
                            label = { Text(stringResource(R.string.editor_password_label)) },
                            supportingText = if (form.hasStoredPassword && form.passwordInput.isEmpty()) {
                                { Text(stringResource(R.string.editor_password_saved_hint)) }
                            } else {
                                null
                            },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (showPassword) R.string.action_hide_password else R.string.action_show_password,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_password_input"),
                        )
                    } else {
                        KeyStatusBanner(form = form)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { keyFilePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_key_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.editor_import_key))
                            }

                            OutlinedButton(
                                onClick = { showGenerateDialog = true },
                                enabled = !form.isGeneratingKey,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("generate_key_button"),
                            ) {
                                if (form.isGeneratingKey) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.editor_generate_key))
                            }
                        }

                        TextButton(
                            onClick = { showManualKeyInput = !showManualKeyInput },
                            modifier = Modifier.testTag("toggle_manual_key_button"),
                        ) {
                            Text(text = stringResource(R.string.editor_paste_key))
                        }

                        AnimatedVisibility(visible = showManualKeyInput) {
                            OutlinedTextField(
                                value = form.privateKeyInput,
                                onValueChange = viewModel::onPrivateKeyChange,
                                label = { Text(stringResource(R.string.editor_private_key_label)) },
                                minLines = 4,
                                maxLines = 8,
                                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_private_key_input"),
                            )
                        }

                        form.generatedKey?.let { generated ->
                            GeneratedPublicKeyCard(
                                generated = generated,
                                onCopy = {
                                    copyToClipboard(context, "SSH Public Key", generated.publicKeyOpenSsh)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.editor_public_key_copied),
                                        )
                                    }
                                },
                            )
                        }

                        OutlinedTextField(
                            value = form.passphraseInput,
                            onValueChange = viewModel::onPassphraseChange,
                            label = { Text(stringResource(R.string.editor_passphrase_label)) },
                            supportingText = if (form.hasStoredPassphrase && form.passphraseInput.isEmpty()) {
                                { Text(stringResource(R.string.editor_passphrase_saved_hint)) }
                            } else {
                                null
                            },
                            singleLine = true,
                            visualTransformation = if (showPassphrase) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                    Icon(
                                        imageVector = if (showPassphrase) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (showPassphrase) R.string.action_hide_password else R.string.action_show_password,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_passphrase_input"),
                        )
                    }
                }

                // Section 3: Behavior & Test
                EditorSectionCard(title = stringResource(R.string.editor_section_options)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.editor_auto_reconnect_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.editor_auto_reconnect_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = form.autoReconnect,
                            onCheckedChange = viewModel::onAutoReconnectChange,
                            modifier = Modifier.testTag("editor_auto_reconnect_switch"),
                        )
                    }

                    val isTesting = testState is ConnectionTestState.Testing ||
                        testState is ConnectionTestState.AwaitingHostKey

                    if (isTesting) {
                        OutlinedButton(
                            onClick = viewModel::cancelTestConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_cancel_test_button"),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = stringResource(R.string.editor_test_cancel))
                        }
                    } else {
                        OutlinedButton(
                            onClick = viewModel::testConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_test_connection_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.editor_test_connection))
                        }
                    }

                    ConnectionTestResultCard(testState = testState)
                }

                Button(
                    onClick = viewModel::save,
                    enabled = !form.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("editor_save_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.editor_save))
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showGenerateDialog) {
        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text(text = stringResource(R.string.editor_generate_key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showGenerateDialog = false
                            viewModel.generateKey(SshKeyAlgorithm.ED25519)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_ed25519_button"),
                    ) {
                        Text(text = stringResource(R.string.editor_generate_ed25519))
                    }
                    OutlinedButton(
                        onClick = {
                            showGenerateDialog = false
                            viewModel.generateKey(SshKeyAlgorithm.RSA_4096)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_rsa_button"),
                    ) {
                        Text(text = stringResource(R.string.editor_generate_rsa))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGenerateDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (testState is ConnectionTestState.AwaitingHostKey) {
        HostKeyVerificationDialog(
            prompt = testState.prompt,
            onTrust = { viewModel.resolveTestHostKey(true) },
            onReject = { viewModel.resolveTestHostKey(false) },
        )
    }
}

@Composable
private fun EditorSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun KeyStatusBanner(form: ServerEditorFormState) {
    val hasKey = form.privateKeyInput.isNotBlank() || form.hasStoredPrivateKey
    val statusColors = LocalStatusColors.current
    val tint = if (hasKey) statusColors.connected else MaterialTheme.colorScheme.onSurfaceVariant
    val label = when {
        form.generatedKey != null ->
            "${stringResource(R.string.editor_key_generated_title)} (${form.generatedKey.algorithm.displayName})"
        form.importedKeyName != null ->
            stringResource(R.string.editor_key_imported, form.importedKeyName)
        form.privateKeyInput.isNotBlank() ->
            stringResource(R.string.editor_key_imported, "PEM / OpenSSH")
        form.hasStoredPrivateKey ->
            stringResource(R.string.editor_private_key_saved_hint)
        else ->
            stringResource(R.string.editor_private_key_empty_hint)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (hasKey) statusColors.connected.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (hasKey) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GeneratedPublicKeyCard(
    generated: GeneratedSshKey,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_key_generated_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = generated.publicKeyOpenSsh,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
            Text(
                text = generated.fingerprintSha256,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.primary,
            )
            FilledTonalButton(
                onClick = onCopy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("copy_public_key_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.editor_copy_public_key))
            }
        }
    }
}

@Composable
private fun ConnectionTestResultCard(testState: ConnectionTestState) {
    val statusColors = LocalStatusColors.current
    when (testState) {
        is ConnectionTestState.Success -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColors.connected.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, statusColors.connected.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_result_success"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = statusColors.connected,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.editor_test_success, testState.latencyMs),
                            style = MaterialTheme.typography.titleSmall,
                            color = statusColors.connected,
                        )
                    }
                    testState.serverBanner?.takeIf { it.isNotBlank() }?.let { banner ->
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (testState.openCodeReachable) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.WarningAmber
                            },
                            contentDescription = null,
                            tint = if (testState.openCodeReachable) statusColors.tunnel else statusColors.warning,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (testState.openCodeReachable) {
                                stringResource(R.string.editor_test_opencode_reachable)
                            } else {
                                stringResource(R.string.editor_test_opencode_unreachable, testState.openCodePort)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        is ConnectionTestState.Failure -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_result_failure"),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = testState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        else -> Unit
    }
}

private fun readPrivateKeyFromUri(context: Context, uri: Uri): Pair<String, String>? {
    return runCatching {
        val resolver = context.contentResolver
        var displayName = "id_key"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(idx) ?: displayName
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            val read = stream.read(buffer)
            if (read <= 0) return null
            buffer.copyOf(read)
        } ?: return null
        val content = String(bytes, Charsets.UTF_8)
        if (content.isBlank()) return null
        displayName to content
    }.getOrNull()
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
