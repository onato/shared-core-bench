package bench.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import bench.android.core.CategoriesResult
import bench.android.core.Query
import bench.android.core.Sort

/** The list's category, price-range and sort choices; the price bounds hold the text as typed. */
class Filters(
    val category: MutableState<String?>,
    val sort: MutableState<Sort>,
    val minPrice: MutableState<String>,
    val maxPrice: MutableState<String>,
) {
    fun query(text: String) =
        Query(text, category.value, minPrice.value.toDoubleOrNull(), maxPrice.value.toDoubleOrNull(), sort.value)

    // Properties, not methods: a lambda or bound reference written in a composable gets a remember branch
    // that only a changed capture reaches, and the gate counts it.
    val chooseCategory: (String) -> Unit = { category.value = it }
    val chooseSort: (Sort) -> Unit = { sort.value = it }
    val clear: () -> Unit = {
        category.value = null
        sort.value = Sort.Id
        minPrice.value = ""
        maxPrice.value = ""
    }
}

@Composable
fun rememberFilters(): Filters {
    val category = rememberSaveable { mutableStateOf<String?>(null) }
    val sort = rememberSaveable { mutableStateOf(Sort.Id) }
    val minPrice = rememberSaveable { mutableStateOf("") }
    val maxPrice = rememberSaveable { mutableStateOf("") }
    return remember { Filters(category, sort, minPrice, maxPrice) }
}

private fun categoryName(category: String) = category

// Built outside the composable, which would otherwise wrap the lambda in a remember branch the gate counts.
private fun namesOf(categories: () -> CategoriesResult): () -> List<String> =
    {
        when (val result = categories()) {
            is CategoriesResult.Categories -> result.names
            CategoriesResult.StorageFailed -> emptyList()
        }
    }

private fun sortName(sort: Sort) = SORT_NAMES.getValue(sort)

private val SORT_NAMES =
    mapOf(
        Sort.Id to "Default",
        Sort.PriceAsc to "Price: low to high",
        Sort.PriceDesc to "Price: high to low",
        Sort.RatingDesc to "Rating: high to low",
    )

// Generated skip branches are unreachable in tests, as for App.
@NonSkippableComposable
@Composable
fun FilterControls(
    filters: Filters,
    categories: () -> CategoriesResult,
) {
    Column {
        Row {
            PriceField("Min price", filters.minPrice, Modifier.weight(1f))
            PriceField("Max price", filters.maxPrice, Modifier.weight(1f))
        }
        Row {
            val label = "Category: ${filters.category.value ?: "All"}"
            Choice(label, namesOf(categories), ::categoryName, filters.chooseCategory)
            Choice("Sort: ${sortName(filters.sort.value)}", Sort::entries, ::sortName, filters.chooseSort)
            TextButton(onClick = filters.clear) { Text("Clear filters") }
        }
    }
}

@NonSkippableComposable
@Composable
private fun PriceField(
    label: String,
    price: MutableState<String>,
    modifier: Modifier,
) {
    val (text, setText) = price
    TextField(text, setText, modifier, label = { Text(label) }, singleLine = true)
}

@NonSkippableComposable
@Composable
private fun <T> Choice(
    label: String,
    options: () -> List<T>,
    name: (T) -> String,
    onChoose: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options().forEach { value ->
                DropdownMenuItem(text = { Text(name(value)) }, onClick = {
                    onChoose(value)
                    expanded = false
                })
            }
        }
    }
}
