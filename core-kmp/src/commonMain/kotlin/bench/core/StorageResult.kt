package bench.core

/** What a [StoragePort] call returns: its value, or the failure that stopped it. */
sealed interface StorageResult<out T>

data class Stored<out T>(
    val value: T,
) : StorageResult<T>

data class StorageError(
    val message: String,
) : StorageResult<Nothing>

/** Runs [next] on what this call stored; the first [StorageError] stops the chain and is its result. */
internal inline fun <T, R> StorageResult<T>.then(next: (T) -> StorageResult<R>): StorageResult<R> =
    when (this) {
        is StorageError -> this
        is Stored -> next(value)
    }

/** Stores each item in turn, stopping at the first [StorageError]. */
internal inline fun <T> Iterable<T>.storeEach(store: (T) -> StorageResult<Unit>): StorageResult<Unit> =
    fold(Stored(Unit)) { stored: StorageResult<Unit>, item -> stored.then { store(item) } }
