.PHONY: help bootstrap test lint coverage test-core test-scripts lint-scripts coverage-scripts \
	test-kmp lint-kmp coverage-kmp test-android lint-android coverage-android bench bench-report

# uv would warn about, then ignore, a VIRTUAL_ENV inherited from another checkout's shell.
unexport VIRTUAL_ENV

GRADLE := core-kmp/gradlew --project-dir core-kmp --console=plain
ANDROID_GRADLE := android/gradlew --project-dir android --console=plain

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

bootstrap: ## Install tools and configure the clone
	uv sync
	git config blame.ignoreRevsFile .git-blame-ignore-revs

test: test-scripts test-kmp test-android ## All tests except benchmarks

lint: lint-scripts lint-kmp lint-android ## All linters; warnings are errors

coverage: coverage-scripts coverage-kmp coverage-android ## Tests with coverage gates

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
	$(ANDROID_GRADLE) :app:testDebugUnitTest :benchmark:testReleaseUnitTest

lint-android:
	$(ANDROID_GRADLE) ktlintCheck detekt

coverage-android:
	$(ANDROID_GRADLE) :app:koverXmlReportApp :app:koverVerifyApp :app:koverLogApp

BENCH_UNSUPPORTED = $(strip $(filter-out all android,$(PLATFORM)) $(filter-out all kmp,$(CORE)) \
	$(filter-out all embedded,$(STORAGE)) $(filter-out all core,$(SUITE)))

bench: ## Run benchmarks on ANDROID_SERIAL and print the table (android, kmp, embedded, core so far)
	@test -z "$(BENCH_UNSUPPORTED)" || { echo "bench: not implemented: $(BENCH_UNSUPPORTED)" >&2; exit 1; }
	@test -n "$(ANDROID_SERIAL)" || { echo "bench: set ANDROID_SERIAL to a simpool lease" >&2; exit 1; }
	rm -rf android/benchmark/build/outputs/connected_android_test_additional_output
	ANDROID_SERIAL=$(ANDROID_SERIAL) $(ANDROID_GRADLE) :benchmark:connectedReleaseAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
	uv run python scripts/bench_collect.py --core kmp --storage embedded --build-type release \
		--git-sha $$(git rev-parse --short HEAD) --out report/results/$$(date +%Y%m%dT%H%M%S) \
		android/benchmark/build/outputs/connected_android_test_additional_output

bench-report: ## Not implemented yet
	@echo "$@: not implemented" >&2; exit 1
