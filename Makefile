.PHONY: help bootstrap test lint coverage test-scripts lint-scripts coverage-scripts bench bench-report

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

bootstrap: ## Install tools and configure the clone
	uv sync
	git config blame.ignoreRevsFile .git-blame-ignore-revs

test: test-scripts ## All tests except benchmarks

lint: lint-scripts ## All linters; warnings are errors

coverage: coverage-scripts ## Tests with coverage gates

test-scripts:
	uv run pytest -q

lint-scripts:
	uv run ruff check scripts
	uv run ruff format --check scripts

coverage-scripts:
	uv run pytest -q --cov

bench bench-report: ## Not implemented yet
	@echo "$@: not implemented" >&2; exit 1
