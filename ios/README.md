# iOS app

`SharedCoreBench` is a SwiftUI app. Its Xcode project is generated from `project.yml` by
XcodeGen and is not checked in; `make test-ios` regenerates it each run. To open it in Xcode:

```bash
make bootstrap                  # XcodeGen, SwiftLint, ViewInspector
cd ios && xcodegen generate && open SharedCoreBench.xcodeproj
```

Tests run on a simulator leased from the pool, never one picked by name or because it is booted
(`CLAUDE.md` → Devices):

```bash
SIM_UDID=$(simpool acquire)
make test-ios SIM_UDID=$SIM_UDID
simpool release $SIM_UDID
```

`make lint-ios` runs SwiftLint with warnings as errors. `make coverage-ios SIM_UDID=…` runs the same
tests with coverage and fails below 100% lines for the app target, naming each file under the gate;
`coverage-exclusions` lists the files it leaves out.
