.PHONY: help bootstrap test lint coverage test-core test-scripts lint-scripts coverage-scripts \
	test-kmp lint-kmp coverage-kmp ios-framework test-ios lint-ios coverage-ios test-android test-android-device lint-android coverage-android fmt bench bench-report sim-udid

# uv would warn about, then ignore, a VIRTUAL_ENV inherited from another checkout's shell.
unexport VIRTUAL_ENV

GRADLE := core-kmp/gradlew --project-dir core-kmp --console=plain
ANDROID_GRADLE := android/gradlew --project-dir android --console=plain
ADB := adb
# A wedged emulator otherwise fails only after Gradle has built for minutes.
DEVICE_CHECK = uv run python scripts/device_check.py --serial $(ANDROID_SERIAL) --adb $(ADB)

# Linux CI has no Xcode; the iOS targets get a macOS CI job of their own (#272).
IS_MACOS := $(filter Darwin,$(shell uname -s))

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

bootstrap: ## Install tools and configure the clone
	uv sync
	$(if $(IS_MACOS),brew install xcodegen swiftlint)
	$(if $(IS_MACOS),cd ios && xcodegen generate --quiet && xcodebuild -resolvePackageDependencies -quiet)
	git config blame.ignoreRevsFile .git-blame-ignore-revs

# test-ios's guard would otherwise fire only after the other legs have run for minutes.
test: $(if $(IS_MACOS),sim-udid) test-scripts test-kmp test-android $(if $(IS_MACOS),test-ios) ## All tests except benchmarks; SIM_UDID on macOS

sim-udid:
	@test -n "$(SIM_UDID)" || { echo "test: set SIM_UDID to a simulator from \`simpool acquire\` (test-ios runs on macOS)" >&2; exit 1; }

lint: lint-scripts lint-kmp lint-android $(if $(IS_MACOS),lint-ios) ## All linters; warnings are errors

coverage: coverage-scripts coverage-kmp coverage-android $(if $(IS_MACOS),coverage-ios) ## Tests with coverage gates

fmt: ## Apply ktlint's fixes to core-kmp and android; detekt's findings still need a hand
	$(GRADLE) ktlintFormat
	$(ANDROID_GRADLE) ktlintFormat
	uv run ruff format scripts

test-core: ## One core's tests: CORE=kmp
	@test -n "$(CORE)" || { echo "test-core: set CORE=kmp" >&2; exit 1; }
	$(MAKE) test-$(CORE)

test-scripts:
	uv run pytest -q

lint-scripts:
	uv run ruff check scripts
	uv run ruff format --check scripts

coverage-scripts:
	uv run pytest -q --cov --cov-report=term-missing --cov-report=xml

test-kmp:
	$(GRADLE) jvmTest testAndroidHostTest :contract-runner:test

lint-kmp:
	$(GRADLE) ktlintCheck detekt

coverage-kmp:
	$(GRADLE) koverXmlReport koverVerify koverLog

test-android: ## Android app unit and Robolectric tests
	$(ANDROID_GRADLE) :app:testDebugUnitTest :benchmark:testReleaseUnitTest :fixtures:testDebugUnitTest :storage:testDebugUnitTest

test-android-device: ## Instrumented UI tests in fixture mode on ANDROID_SERIAL (not part of make test)
	@test -n "$(ANDROID_SERIAL)" || { echo "test-android-device: set ANDROID_SERIAL to a simpool lease" >&2; exit 1; }
	@$(DEVICE_CHECK)
	ANDROID_SERIAL=$(ANDROID_SERIAL) $(ANDROID_GRADLE) :uitest:connectedDebugAndroidTest

ios-framework: ## Build core-kmp's SharedCore.xcframework (CONFIG=Debug|Release, default Debug)
	$(GRADLE) assembleSharedCore$(or $(CONFIG),Debug)XCFramework

test-ios: ## iOS app unit tests on SIM_UDID
	@test -n "$(SIM_UDID)" || { echo "test-ios: set SIM_UDID to a simulator from \`simpool acquire\`" >&2; exit 1; }
	$(MAKE) ios-framework
	cd ios && xcodegen generate --quiet
	xcodebuild test -quiet -project ios/SharedCoreBench.xcodeproj -scheme SharedCoreBench \
		-destination id=$(SIM_UDID)

IOS_COVERAGE := ios/build/coverage

coverage-ios: ## iOS app unit tests on SIM_UDID, failing below 100% lines
	@test -n "$(SIM_UDID)" || { echo "coverage-ios: set SIM_UDID to a simulator from \`simpool acquire\`" >&2; exit 1; }
	$(MAKE) ios-framework
	cd ios && xcodegen generate --quiet
	rm -rf $(IOS_COVERAGE).xcresult
	xcodebuild test -quiet -project ios/SharedCoreBench.xcodeproj -scheme SharedCoreBench \
		-destination id=$(SIM_UDID) -enableCodeCoverage YES -resultBundlePath $(IOS_COVERAGE).xcresult
	xcrun xccov view --report --json $(IOS_COVERAGE).xcresult > $(IOS_COVERAGE).json
	uv run python scripts/coverage_ios.py $(IOS_COVERAGE).json --root $(CURDIR)/ios \
		--exclusions ios/coverage-exclusions

lint-ios:
	cd ios && swiftlint lint --strict --quiet

lint-android:
	$(ANDROID_GRADLE) ktlintCheck detekt

coverage-android:
	$(ANDROID_GRADLE) :app:koverXmlReportApp :app:koverVerifyApp :app:koverLogApp

BENCH_UNSUPPORTED = $(strip $(filter-out all android,$(PLATFORM)) $(filter-out all kmp,$(CORE)) \
	$(filter-out all embedded batched chatty,$(STORAGE)) $(filter-out all core boundary app,$(SUITE)))

BOUNDARY_BENCHMARK = bench.benchmark.BoundaryBenchmark
BENCH_SUITE_core = -Pandroid.testInstrumentationRunnerArguments.notClass=$(BOUNDARY_BENCHMARK)
BENCH_SUITE_boundary = -Pandroid.testInstrumentationRunnerArguments.class=$(BOUNDARY_BENCHMARK)
# The delegated variants run only benchmarks 7–9; the others never touch storage.
BENCH_CLASSES_embedded = $(BENCH_SUITE_$(SUITE))
BENCH_CLASSES_batched = \
	-Pandroid.testInstrumentationRunnerArguments.class=bench.benchmark.IngestBenchmark,bench.benchmark.CatalogueBenchmark
BENCH_CLASSES_chatty = $(BENCH_CLASSES_batched)
BENCH_STORAGES = $(filter $(if $(filter-out all,$(STORAGE)),$(STORAGE),embedded batched chatty), \
	embedded $(if $(filter boundary,$(SUITE)),,batched chatty))

BENCH_OUTPUT = android/benchmark/build/outputs/connected_android_test_additional_output
BENCH_RESULTS := report/results/$(shell date +%Y%m%dT%H%M%S)

# One storage variant's run, collected into the shared results so the table compares them.
define BENCH_RUN
	rm -rf $(BENCH_OUTPUT)
	ANDROID_SERIAL=$(ANDROID_SERIAL) $(ANDROID_GRADLE) :benchmark:connectedReleaseAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
		-Pandroid.testInstrumentationRunnerArguments.storage=$(1) $(BENCH_CLASSES_$(1))
	uv run python scripts/bench_collect.py --core kmp --storage $(1) --build-type release \
		--git-sha $(shell git rev-parse --short HEAD) --out $(BENCH_RESULTS) $(BENCH_OUTPUT)

endef

# SUITE=app is the Macrobenchmark module, run alone against the app's `benchmark` build.
APP_BENCH_OUTPUT = android/macrobenchmark/build/outputs/connected_android_test_additional_output
define BENCH_APP
	rm -rf $(APP_BENCH_OUTPUT)
	ANDROID_SERIAL=$(ANDROID_SERIAL) $(ANDROID_GRADLE) :macrobenchmark:connectedBenchmarkAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
	uv run python scripts/bench_collect.py --core kmp --storage embedded --build-type release \
		--git-sha $(shell git rev-parse --short HEAD) --out $(BENCH_RESULTS) $(APP_BENCH_OUTPUT)

endef

bench: ## Run benchmarks on ANDROID_SERIAL and print the table (android, kmp; core|boundary, or app alone)
	@test -z "$(BENCH_UNSUPPORTED)" || { echo "bench: not implemented: $(BENCH_UNSUPPORTED)" >&2; exit 1; }
	@test -n "$(ANDROID_SERIAL)" || { echo "bench: set ANDROID_SERIAL to a simpool lease" >&2; exit 1; }
	@$(DEVICE_CHECK)
	$(if $(filter app,$(SUITE)),$(BENCH_APP),$(foreach storage,$(BENCH_STORAGES),$(call BENCH_RUN,$(storage))))

bench-report: ## Not implemented yet
	@echo "$@: not implemented" >&2; exit 1
