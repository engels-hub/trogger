package lv.papart.trogger

import java.util.Locale

/**
 * UI language. Only the interface is translated: exports, ceļazīmes and values stored
 * in the DB (categories, default purpose) stay in Latvian, as VID expects.
 */
val LANGUAGES = listOf("lv" to "Latviešu", "ru" to "Русский")

fun defaultLang(): String = if (Locale.getDefault().language == "ru") "ru" else "lv"

fun isRu(): Boolean = Repo.settings.value.lang == "ru"

/** Pick the UI string for the current language. */
fun tr(lv: String, ru: String): String = if (isRu()) ru else lv

fun kmUnit(): String = tr("km", "км")

private val MONTHS_RU = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)

fun monthName(m: Int): String = if (isRu()) MONTHS_RU[m] else MONTHS_LV[m]

private val CATEGORIES_RU = mapOf(
    "Degviela" to "Топливо",
    "Remonts un apkope" to "Ремонт и обслуживание",
    "Riepas" to "Шины",
    "OCTA" to "OCTA",
    "KASKO" to "KASKO",
    "Tehniskā apskate" to "Техосмотр",
    "Ekspluatācijas nodoklis" to "Налог на эксплуатацию",
    "Stāvvieta" to "Парковка",
    "Mazgāšana" to "Мойка",
    "Cits" to "Другое",
)

/** Categories are stored in Latvian; this is only how they are shown. */
fun categoryLabel(c: String): String = if (isRu()) CATEGORIES_RU[c] ?: c else c

/** Period label in the UI language; [Period.label] stays Latvian for exports. */
fun Period.uiLabel(): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = from }
    val year = c.get(java.util.Calendar.YEAR)
    return if (to - from > 32L * 24 * 3600 * 1000) "$year"
    else "${monthName(c.get(java.util.Calendar.MONTH))} $year"
}
