package com.healthhelper.app.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthhelper.app.domain.model.GlucoseMealType
import com.healthhelper.app.domain.model.GlucoseUnit
import com.healthhelper.app.domain.model.RelationToMeal
import com.healthhelper.app.domain.model.SpecimenSource
import com.healthhelper.app.presentation.viewmodel.GlucoseConfirmationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseConfirmationScreen(
    onNavigateHome: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: GlucoseConfirmationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigateHome.collect { message ->
            onNavigateHome(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm Glucose") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${uiState.valueMgDl} mg/dL (${uiState.displayMmolL} mmol/L)",
                style = MaterialTheme.typography.headlineMedium,
            )

            if (uiState.detectedUnit == GlucoseUnit.MG_DL && uiState.originalValue.isNotEmpty()) {
                Text(
                    text = "Converted from ${uiState.originalValue} mg/dL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = uiState.valueMgDl,
                onValueChange = viewModel::updateValue,
                label = { Text("Glucose (mg/dL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = if (uiState.displayMmolL.isNotEmpty()) {
                    { Text("${uiState.displayMmolL} mmol/L") }
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

            uiState.validationError?.let { validationError ->
                Text(
                    text = validationError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            uiState.warning?.let { warning ->
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
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
                    Text("Retake")
                }
                Button(
                    onClick = viewModel::save,
                    enabled = uiState.isSaveEnabled && !uiState.isSaving,
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
