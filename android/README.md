# Android app

## Testing Compose under Robolectric

The app's screen tests (`app/src/test/kotlin/bench/android/`) run Compose on Robolectric. Each
of these traps cost a session several runs:

- **A Compose test class whose tests change what a flow loads (the search text, a filter)
  chains `DrainMainLooper` outside its compose rule:**
  `RuleChain.outerRule(DrainMainLooper()).around(compose)`, as `ProductListTest` does.
  `ProductDetailTest` doesn't need it yet; add it with the first such test there. Compose's
  main dispatcher is shared by the whole JVM. If Robolectric drops a post it queued while a test
  tore down, every later test stalls on "Loading" and times out, including tests in other
  classes. Only a rule outside the compose rule runs late enough to fix that. `waitForIdle`,
  advancing the clock, or idling the looper inside the test don't help (#155).
- **Material3 `ListItem` merges its children's semantics.** Finding a text in a cell returns the
  merged node, which holds several texts, so `.single()` or a style check on the node fails. Pass
  `useUnmergedTree = true` to reach the `Text` itself, as `ProductListTest`'s `decorationsOf` does
  (#149).
- **The app's Kover gate counts branches (95%), and Compose generates them.** Splitting a
  screen into its own `@Composable` adds skip and lambda-capture branches that tests never reach.
  #151 measured them taking the app from 100% to 86%. A whole screen takes
  `@NonSkippableComposable`, as `App` and `ProductDetailScreen` do. Smaller pieces stay inline in
  their screen.
- **A lambda or bound reference written in a composable adds a branch only a changed capture
  reaches.** `{ state.value = it }` or `holder::clear` passed to a control is remembered against
  what it captures, and tests never recompose with a new capture. Pass a function-typed property
  of a remembered holder, a top-level `::function`, or `MutableState`'s `component2()` instead, as
  `Filters.kt` does (#112).
- **An exhaustive `when` over a sealed result in a composable leaves an uncovered line.** Kotlin
  adds a `NoWhenBranchMatchedException` throw that no test reaches, and Kover doesn't filter it
  under the Compose compiler. Make the last branch `else`, as `LoadedProductDetail` does (#243).
- **App tests run the app's background work on `Dispatchers.Main`, then call `waitForIdle()`
  and assert.** Pass it as the `background` parameter of `App`, `productPages` or `BenchApp`.
  Under Robolectric that is the main looper, which `waitForIdle()` drains, so the load is done
  when it returns. `NoPollingInAppTestsTest` fails on any `waitUntil` in `app/src/test`
  (#257, #299).
- **Getting such a test red on `Dispatchers.IO` takes a slow fake.** The fakes answer so fast
  that the load usually beats the assertion even on IO: `StartupReportTest` passed 4 of 4 runs
  and `ProductListTest` failed 1 of 23, which review rejected as a flaky red. For the red run,
  add a temporary `Thread.sleep` to `FakeSharedCore.page` (200 ms was enough under
  `createComposeRule()`), don't commit it, and say so in the PR. `createAndroidComposeRule`'s
  `waitForIdle()` waits out most IO work too: with 2 s per page `ProductDetailTest` still passed
  and `SettingsTest` failed one test of three. When a test can't go red, say why in the PR, with
  the measurement (#300, #302).
- **Assert on text only the new screen shows.** The detail screen repeats the product's title,
  so a test that taps a product and waits for its title passes before navigation exists (#151).

## Build and test traps outside Compose

- **No `BundledSQLiteDriver` under Robolectric.** Its native library loads once per JVM. Once
  another Robolectric class has loaded it, the next one fails with `UnsatisfiedLinkError: …
  already loaded in another classloader`. A custom runner can pass when its class runs alone and
  still fail in the full suite. Make the code take a database path and a `DriverFactory`, as
  `AndroidCores` does, and test it with a plain JUnit class (`AndroidCoresTest`, on `JvmDrivers`)
  (#215).
- **A `private companion object` holding a non-`const` `val` leaves Kover one uncovered
  instruction.** `PortCores` kept `listOf(…)` in one and its module fell to 99.8%; a file-level
  `private val` fixed it (#248). A `const val` there is fine, as `EmbeddedCores` shows. Like the
  exhaustive `when` above, it is code the compiler generates and no test can reach.
- **The app can't call the core's `inline` functions.** `core-kmp` builds for JVM 21 and `app`
  builds for JVM 11, so `:app:compileDebugKotlin` fails with `Cannot inline bytecode built with
  JVM target 21`. Use a non-inline function in the app (#221).
- **Some code sits at a detekt size limit.** `config/detekt/detekt.yml` keeps detekt's default
  thresholds, in both `android/` and `core-kmp/`. Check the code an issue touches against these
  before writing it:

  | Rule | Fails at | At or near it |
  |---|---|---|
  | `TooManyFunctions` | 11 functions in a class | `Core`, `SqliteStoragePort` (#221) |
  | `ReturnCount` | 3 returns in a function | `ProductPagingSource.load`, suppressed with its reason (#249) |
  | `LongMethod` | 61 lines | `App`, 58 after #250 moved the detail branch out |
  | `CyclomaticComplexMethod` | complexity 15 | `App` (#250) |

  Plan where new code goes (a file-level function, a small helper type or composable) before
  adding it. Don't reshape code already on `main` to fit; that is a `Structural:` PR first.
  #249 folded `main`'s code into a `when` to fit `ReturnCount`, and review failed it.

## Running the benchmarks

`make bench SUITE=core ANDROID_SERIAL=<serial>` builds the release benchmark APK, runs the
microbenchmarks on the leased device and prints one table row per workload and N. On an emulator
it took about 13 minutes (#202), most of it in the two 10 000-row paging benchmarks.

- **adb must answer first.** `make bench` and `make test-android-device` run
  `adb -s <serial> shell getprop ro.product.model` before Gradle and stop at once when it fails or
  takes over 5 seconds (`scripts/device_check.py`). When adb itself is wedged, Gradle fails with
  `TimeoutException getting properties for device` on every emulator, so the run never starts
  (#122). Restart the adb server or lease another emulator rather than retrying the bench.
- `STORAGE` picks the storage variants (`all`, the default, runs every one). Each variant is its
  own instrumentation run, passing `storage=<variant>` to the benchmarks; `batched` and `chatty`
  run only `IngestBenchmark` and `CatalogueBenchmark`, on the app's `SqliteStoragePort`. All runs
  land in one results file, and the table adds each delegated row's overhead over embedded.
- Results land in `report/results/<timestamp>/results.json`, which is gitignored; paste the
  printed table into the PR.
