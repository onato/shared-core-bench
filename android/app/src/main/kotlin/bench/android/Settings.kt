package bench.android

import android.content.Context

/** The [CoreChoice] the app keeps across restarts. */
class Settings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var choice: CoreChoice
        get() =
            CoreChoice(
                CoreKind.valueOf(prefs.getString(CORE, null) ?: CoreKind.Kmp.name),
                Storage.valueOf(prefs.getString(STORAGE, null) ?: Storage.Embedded.name),
            )
        set(value) =
            prefs
                .edit()
                .putString(CORE, value.core.name)
                .putString(STORAGE, value.storage.name)
                .apply()

    private companion object {
        const val CORE = "core"
        const val STORAGE = "storage"
    }
}

enum class CoreKind(
    val label: String,
) {
    Kmp("KMP"),
}

enum class Storage(
    val label: String,
) {
    Embedded("Embedded"),
    Batched("Delegated, batched"),
    Chatty("Delegated, chatty"),
}

data class CoreChoice(
    val core: CoreKind,
    val storage: Storage,
)
