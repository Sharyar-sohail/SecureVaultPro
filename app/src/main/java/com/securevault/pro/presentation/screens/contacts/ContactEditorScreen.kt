package com.securevault.pro.presentation.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.ContactCategories
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.components.Glyph

@Composable
fun ContactEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ContactEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onSaved()
    }

    GlassScaffold(
        title = if (state.isNew) "New contact" else "Edit contact",
        subtitle = if (state.decoy) "Decoy vault · read only" else "Every field encrypted at rest",
        onBack = onBack,
        actions = {
            if (!state.isNew && !state.decoy) {
                GlassIconButton(
                    icon = Glyph.delete,
                    contentDescription = "Delete contact",
                    onClick = { confirmDelete = true },
                    tint = VaultColor.debit
                )
            }
        }
    ) { padding ->
        if (state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = VaultColor.accent)
            }
            return@GlassScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonogramBadge(
                        seed = state.name.ifBlank { "New contact" },
                        size = 54.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.name.ifBlank { "Unnamed contact" },
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = state.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
            }

            VaultTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = "Full name",
                placeholder = "Ada Lovelace",
                leadingIcon = Glyph.person,
                imeAction = ImeAction.Next
            )
            SectionHeader(title = "Category")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ContactCategories) { category ->
                    Pill(
                        label = category,
                        selected = state.category == category,
                        onClick = { viewModel.setCategory(category) }
                    )
                }
            }
            VaultTextField(
                value = state.phone,
                onValueChange = viewModel::setPhone,
                label = "Phone",
                placeholder = "+1 555 0100",
                leadingIcon = Glyph.phone,
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.email,
                onValueChange = viewModel::setEmail,
                label = "Email",
                placeholder = "name@example.com",
                leadingIcon = Glyph.email,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.address,
                onValueChange = viewModel::setAddress,
                label = "Address",
                leadingIcon = Glyph.locationOn,
                singleLine = false,
                minLines = 2,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = "Secure notes",
                placeholder = "Relationship, safe word, account numbers…",
                leadingIcon = Glyph.description,
                singleLine = false,
                minLines = 4,
                imeAction = ImeAction.Default
            )

            if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.debit,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            GradientButton(
                text = if (state.isNew) "Save contact" else "Update contact",
                icon = Glyph.person,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
                onClick = viewModel::save
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "Delete this contact?",
        message = "Every phone number, email and note for this contact will be permanently erased.",
        confirmLabel = "Delete",
        onConfirm = {
            confirmDelete = false
            viewModel.delete()
        },
        onDismiss = { confirmDelete = false }
    )
}
