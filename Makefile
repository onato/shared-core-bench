.PHONY: help bootstrap test lint coverage test-core test-scripts lint-scripts coverage-scripts \
	test-kmp lint-kmp coverage-kmp bench bench-report

GRADLE := core-kmp/gradlew --project-dir core-kmp --console=plain

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

bootstrap: ## Install tools and configure the clone
	uv sync
	git config blame.ignoreRevsFile .git-blame-ignore-revs

test: test-scripts test-kmp ## All tests except benchmarks

lint: lint-scripts lint-kmp ## All linters; warnings are errors

coverage: coverage-scripts coverage-kmp ## Tests with coverage gates

test-core: ## One core's tests: CORE=kmp
	@test -n "$(CORE)" || { echo "test-core: set CORE=kmp" >&2; exit 1; }
	$(MAKE) test-$(CORE)

test-scripts:
	uv run pytest -q

lint-scripts:
	uv run ruff check scripts
	uv run ruff format --check scripts

coverage-scripts:
	uv run pytest -q --cov

test-kmp:
	$(GRADLE) jvmTest testAndroidHostTest

lint-kmp:
	$(GRADLE) ktlintCheck detekt

coverage-kmp:
	$(GRADLE) koverVerify koverLog

bench bench-report: ## Not implemented yet
	@echo "$@: not implemented" >&2; exit 1
