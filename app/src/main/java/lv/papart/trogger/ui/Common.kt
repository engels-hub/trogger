package lv.papart.trogger.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lv.papart.trogger.fmtDate
import lv.papart.trogger.fmtNum
import lv.papart.trogger.fmtTime
import java.util.Calendar

private val Carmine = Color(0xFF9E3039)

@Composable
fun TroggerTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFFFB3B4), onPrimary = Color(0xFF5F1219),
        primaryContainer = Color(0xFF7D1D27), onPrimaryContainer = Color(0xFFFFDADA),
    ) else lightColorScheme(
        primary = Carmine, onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDADA), onPrimaryContainer = Color(0xFF40000A),
        background = Color(0xFFF7F7F2), surface = Color(0xFFF7F7F2),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun StatRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Full-screen form used for every add/edit dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Aizvērt") }
                    },
                    actions = {
                        if (onDelete != null) IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, "Dzēst")
                        }
                        TextButton(onClick = onSave, enabled = saveEnabled) { Text("Saglabāt") }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Dzēst ierakstu?") },
            text = { Text("To nevarēs atjaunot.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Dzēst") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Atcelt") } },
        )
    }
}

@Composable
fun TextIn(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = singleLine, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NumIn(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), suffix: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        suffix = if (suffix.isEmpty()) null else ({ Text(suffix) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun numText(v: Double?, decimals: Int = 0): String = v?.let { fmtNum(it, decimals) } ?: ""

/** Date and time buttons that open the platform pickers and keep the other part of [ms]. */
@Composable
fun DateTimeRow(label: String, ms: Long, onChange: (Long) -> Unit, showTime: Boolean = true) {
    val ctx = LocalContext.current
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row {
            OutlinedButton(onClick = {
                DatePickerDialog(ctx, { _, y, m, d ->
                    onChange(Calendar.getInstance().apply { timeInMillis = ms; set(y, m, d) }.timeInMillis)
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text(fmtDate(ms)) }
            if (showTime) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, min ->
                        onChange(Calendar.getInstance().apply {
                            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                        }.timeInMillis)
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }) { Text(fmtTime(ms)) }
            }
        }
    }
}
