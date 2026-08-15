# CloFin — unified entrypoint.
#
# Everything you need to run, test and inspect CloFin is a target in this file.
# Only two prerequisites are assumed: Docker with Compose v2, and GNU Make.
# A local Clojure CLI and JDK 21 are optional and are used automatically when
# present (faster feedback); otherwise the same commands run in a container.
#
# See docs/ADR/0005-hybrid-local-and-cloud-execution.md.

SHELL       := /bin/sh
COMPOSE     := docker compose
CLOJURE     := $(shell command -v clojure 2>/dev/null)
ENV_FILE    := .env
HTTP_PORT   ?= 8080
BASE_URL    ?= http://localhost:$(HTTP_PORT)

# Run Clojure natively when a local CLI exists, otherwise inside a throwaway
# container that shares the Maven cache so repeated runs stay fast.
ifeq ($(CLOJURE),)
  CLJ = $(COMPOSE) run --rm toolchain clojure
else
  CLJ = clojure
endif

# The commit the stack reports on `GET /`. Resolved here because the container
# image contains no repository — `git rev-parse HEAD` in the image would find
# nothing, and an image that guessed would be worse than one that says
# "unknown". Overridable, and empty is a valid answer: an environment with no
# git available simply reports "unknown", which is true.
#
# `rev-parse HEAD` and not `--abbrev-ref` or `describe`: the service refuses
# anything that is not a full commit id, so a branch name passed here is
# ignored rather than published (011-REQ O-1, applied server-side).
CLOFIN_SOURCE_COMMIT ?= $(shell git rev-parse HEAD 2>/dev/null)

# Origins allowed to read this instance's responses from a browser page.
# **Empty by default** — that is the setting under which CloFin sends no CORS
# header at all. Set it in `.env`, or for one run:
#   make up CLOFIN_CORS_ALLOWED_ORIGINS=https://echojustus.github.io
# See docs/ADR/0027.
CLOFIN_CORS_ALLOWED_ORIGINS ?=

# Exported only when they have a value. An exported *empty* variable would take
# precedence over `.env` in Compose and silently override whatever the operator
# wrote there — which for the CORS allowlist would mean a setting that appears
# to be configured and is not.
ifneq ($(CLOFIN_SOURCE_COMMIT),)
  export CLOFIN_SOURCE_COMMIT
endif
ifneq ($(CLOFIN_CORS_ALLOWED_ORIGINS),)
  export CLOFIN_CORS_ALLOWED_ORIGINS
endif

.DEFAULT_GOAL := help

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@echo "CloFin — enterprise payments & reconciliation core"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@awk 'BEGIN {FS = ":.*?## "} \
	     /^# ---/ {next} \
	     /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' \
	     $(MAKEFILE_LIST)
	@echo ""
	@echo "CloFin uses synthetic data only. It is not connected to any bank,"
	@echo "payment scheme or central bank, and holds no regulatory approval."

# ---------------------------------------------------------------------------
# Local environment
# ---------------------------------------------------------------------------

.PHONY: env
env: $(ENV_FILE) ## Create .env from .env.example if missing

$(ENV_FILE):
	@cp .env.example $(ENV_FILE)
	@echo "Created $(ENV_FILE) from .env.example"

# ---------------------------------------------------------------------------
# Stack
# ---------------------------------------------------------------------------

.PHONY: up
up: env ## Start the full stack (PostgreSQL + CloFin service)
	$(COMPOSE) up -d --build postgres app
	@echo "CloFin starting. Try: make health"
	@echo "GET / will report sourceCommit=$(if $(CLOFIN_SOURCE_COMMIT),$(CLOFIN_SOURCE_COMMIT),unknown) (self-reported, not attested)"
	@echo "Browser origins allowed to read responses: $(if $(CLOFIN_CORS_ALLOWED_ORIGINS),$(CLOFIN_CORS_ALLOWED_ORIGINS),none — see .env.example)"

.PHONY: down
down: ## Stop the stack, keeping data volumes
	$(COMPOSE) down

.PHONY: destroy
destroy: ## Stop the stack and delete all data volumes
	$(COMPOSE) down -v

.PHONY: restart
restart: down up ## Restart the stack

.PHONY: ps
ps: ## Show container status
	$(COMPOSE) ps

.PHONY: logs
logs: ## Follow service logs
	$(COMPOSE) logs -f app

.PHONY: build
build: ## Build the CloFin container image
	$(COMPOSE) build app

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

.PHONY: db-up
db-up: env ## Start PostgreSQL only (for host-side development)
	$(COMPOSE) up -d postgres
	@echo "Waiting for PostgreSQL to accept connections..."
	@$(COMPOSE) exec -T postgres sh -c 'until pg_isready -q -U "$$POSTGRES_USER"; do sleep 1; done'
	@echo "PostgreSQL ready."

.PHONY: db-shell
db-shell: ## Open a psql shell against the local database
	$(COMPOSE) exec postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"'

.PHONY: db-reset
db-reset: ## Drop and recreate the database, then migrate
	$(COMPOSE) exec -T postgres sh -c 'dropdb -U "$$POSTGRES_USER" --if-exists "$$POSTGRES_DB" && createdb -U "$$POSTGRES_USER" "$$POSTGRES_DB"'
	@$(MAKE) migrate

.PHONY: migrate
migrate: ## Apply pending database migrations
	$(CLJ) -M -m clofin.db.migrate

.PHONY: migrate-status
migrate-status: ## Show applied and pending migrations
	$(CLJ) -M -m clofin.db.migrate status

# ---------------------------------------------------------------------------
# Development
# ---------------------------------------------------------------------------

.PHONY: run
run: ## Run the service on the host (requires PostgreSQL: make db-up)
	$(CLJ) -M:run

.PHONY: repl
repl: ## Start a development REPL
	$(CLJ) -M:dev -r

.PHONY: deps
deps: ## Download all dependencies without running anything
	$(CLJ) -P -M:test:dev

# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------

.PHONY: test
test: ## Run unit and property tests (no database required)
	$(CLJ) -M:test

.PHONY: test-it
test-it: db-up migrate ## Run all tests including database integration tests
	$(CLJ) -M:test:it

.PHONY: health
health: ## Check that a running service reports healthy
	@curl -fsS $(BASE_URL)/healthz && echo "" || (echo "CloFin is not responding on $(BASE_URL)"; exit 1)

.PHONY: ready
ready: ## Check that a running service reports ready (database reachable)
	@curl -fsS $(BASE_URL)/readyz && echo "" || (echo "CloFin is not ready on $(BASE_URL)"; exit 1)

.PHONY: smoke
smoke: up ## Bring the stack up and verify it answers health and readiness
	@echo "Waiting for CloFin to become ready..."
	@i=0; until curl -fsS $(BASE_URL)/readyz >/dev/null 2>&1; do \
	  i=$$((i+1)); \
	  if [ $$i -gt 60 ]; then echo "Timed out waiting for $(BASE_URL)/readyz"; $(COMPOSE) logs app; exit 1; fi; \
	  sleep 2; \
	done
	@$(MAKE) --no-print-directory health
	@$(MAKE) --no-print-directory ready
	@echo "Smoke test passed."

.PHONY: docs-check
docs-check: ## Verify that every internal documentation link resolves
	@sh scripts/check-doc-links.sh

.PHONY: diagrams
diagrams: ## Regenerate every committed diagram from its source of truth
	$(CLJ) -M:diagrams

.PHONY: diagrams-check
diagrams-check: ## Fail if a committed diagram no longer matches its source
	@$(CLJ) -M:diagrams --check

.PHONY: doc-consistency
doc-consistency: ## Report where the status documents contradict each other
	@sh scripts/check-doc-consistency.sh

# ---------------------------------------------------------------------------
# Capture — the fixtures `clofin-trace` replays (ADR-0020 RULE 2)
# ---------------------------------------------------------------------------

CAPTURE_REF ?= ref-1
CAPTURE_OUT ?= target/capture

# Starts the *tagged* commit's own service from a detached worktree, so this
# one needs a local Clojure CLI and a reachable PostgreSQL rather than the
# container fallback: which commit built the running stack is the whole point,
# and a toolchain container decides that for you. The capture database is
# scratch — it is dropped and recreated — which is why its name must end in
# `_capture`.
.PHONY: capture-trace
capture-trace: ## Capture the replay bundles for clofin-trace from a tagged commit
	clojure -M:capture --ref $(CAPTURE_REF) --out $(CAPTURE_OUT)

# Re-reads the GitHub release body for each tag mirrored in docs/releases/ and
# fails if the committed copy has drifted. Not in `verify`: it needs the
# network, and a verification that fails offline is one people learn to skip.
.PHONY: check-release-annotation
check-release-annotation: ## Compare docs/releases/*.annotation.txt with the published release
	@sh scripts/check-release-annotation.sh

# `doc-consistency` entered `verify` on 2026-08-12, once the O-1 ROADMAP
# staleness was corrected on `meta` and re-synced (006-REQ objection O-1;
# the deferral it replaces was pinned by a test, deleted in the same commit).
# A failure here means two documents disagree about what is built — fix the
# stale one on `meta` if it is a governance document, never in place.
.PHONY: verify
verify: test docs-check diagrams-check doc-consistency ## Everything CI runs that does not need a database

# ---------------------------------------------------------------------------
# Housekeeping
# ---------------------------------------------------------------------------

.PHONY: clean
clean: ## Remove build and dependency caches
	rm -rf .cpcache target classes
