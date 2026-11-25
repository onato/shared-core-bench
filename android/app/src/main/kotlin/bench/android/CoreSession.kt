package bench.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import bench.android.core.SharedCore

/** Opens the core a [CoreChoice] names; [empty] first deletes its database. */
fun interface CoreFactory {
    fun open(
        choice: CoreChoice,
        empty: Boolean,
    ): SharedCore
}

/** The core the app runs on, opened from the chosen [CoreChoice]. */
class CoreSession(
    private val settings: Settings,
    private val cores: CoreFactory,
) {
    var choice by mutableStateOf(settings.choice)
        private set
    var core by mutableStateOf(cores.open(choice, empty = false))
        private set

    /** Switches to [next], on an empty database so no variant reads another's rows. */
    fun choose(next: CoreChoice) {
        choice = next
        settings.choice = next
        core = cores.open(next, empty = true)
    }

    // A property, not a lambda written in the screen: that gets a remember branch only a changed capture reaches.
    val chooseStorage: Map<Storage, () -> Unit> =
        Storage.entries.associateWith { storage -> { choose(choice.copy(storage = storage)) } }
}
