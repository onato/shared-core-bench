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
- **Assert on text only the new screen shows.** The detail screen repeats the product's title,
  so a test that taps a product and waits for its title passes before navigation exists (#151).

## Running the benchmarks

`make bench SUITE=core ANDROID_SERIAL=<serial>` builds the release benchmark APK, runs the
microbenchmarks on the leased device and prints one table row per workload and N. On an emulator
it took about 13 minutes (#202), most of it in the two 10 000-row paging benchmarks.

- **Check that adb answers before starting.** `adb -s <serial> shell getprop ro.product.model`
  should print the model within a second or two. When adb itself is wedged, Gradle fails with
  `TimeoutException getting properties for device` on every emulator, so the run never starts
  (#122). Restart the adb server or lease another emulator rather than retrying the bench.
- Results land in `report/results/<timestamp>/results.json`, which is gitignored; paste the
  printed table into the PR.
