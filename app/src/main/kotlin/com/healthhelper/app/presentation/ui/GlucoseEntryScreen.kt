package com.healthhelper.app.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.presentation.viewmodel.GlucoseEntryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseEntryScreen(
    onNavigateHome: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: GlucoseEntryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturePhoto = rememberPhotoCapture(
        cacheSubDir = "glucose_images",
        onPhoto = viewModel::onPhotoCaptured,
        onError = viewModel::onCaptureError,
    )

    LaunchedEffect(Unit) {
        viewModel.navigateHome.collect { message ->
            onNavigateHome(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blood Glucose") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // innerPadding already covers the nav bar; don't add it again under the keyboard.
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScanFromPhotoSection(
                isScanning = uiState.isScanning,
                filledFromPhoto = uiState.filledFromPhoto,
                scanError = uiState.scanError,
                enabled = !uiState.isSaving,
                onScan = capturePhoto,
            )

            uiState.conversionNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = uiState.valueMgDl,
                onValueChange = viewModel::updateValue,
                enabled = !uiState.isScanning,
                label = { Text("Glucose (mg/dL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = uiState.validationError != null,
                supportingText = if (uiState.displayMmolL.isNotEmpty() || uiState.validationError != null) {
                    {
                        Column {
                            if (uiState.displayMmolL.isNotEmpty()) {
                                Text("${uiState.displayMmolL} mmol/L")
                            }
                            uiState.validationError?.let { validationError ->
                                Text(
                                    text = validationError,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }
                    }
                } else {
                    null
                },
            )

            RelationToMealDropdown(
                selected = uiState.relationToMeal,
                onSelected = viewModel::updateRelationToMeal,
            )

            AnimatedVisibility(visible = uiState.mealTypeVisible) {
                GlucoseMealTypeDropdown(
                    selected = uiState.glucoseMealType,
                    onSelected = viewModel::updateGlucoseMealType,
                )
            }

            SpecimenSourceDropdown(
                selected = uiState.specimenSource,
                onSelected = viewModel::updateSpecimenSource,
            )

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = viewModel::save,
                    enabled = uiState.isSaveEnabled && !uiState.isSaving && !uiState.isScanning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isSaving) "Saving..." else "Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelationToMealDropdown(
    selected: RelationToMeal,
    onSelected: (RelationToMeal) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = formatEnumName(selected.name),
            onValueChange = {},
            readOnly = true,
            label = { Text("Relation to Meal") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RelationToMeal.entries.forEach { relation ->
                DropdownMenuItem(
                    text = { Text(formatEnumName(relation.name)) },
                    onClick = {
                        onSelected(relation)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlucoseMealTypeDropdown(
    selected: GlucoseMealType,
    onSelected: (GlucoseMealType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = formatEnumName(selected.name),
            onValueChange = {},
            readOnly = true,
            label = { Text("Meal Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GlucoseMealType.entries.forEach { mealType ->
                DropdownMenuItem(
                    text = { Text(formatEnumName(mealType.name)) },
                    onClick = {
                        onSelected(mealType)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpecimenSourceDropdown(
    selected: SpecimenSource,
    onSelected: (SpecimenSource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = formatEnumName(selected.name),
            onValueChange = {},
            readOnly = true,
            label = { Text("Specimen Source") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SpecimenSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text(formatEnumName(source.name)) },
                    onClick = {
                        onSelected(source)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatEnumName(name: String): String =
    name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
