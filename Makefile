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

.PHONY: verify
verify: test docs-check ## Everything CI runs that does not need a database

# ---------------------------------------------------------------------------
# Housekeeping
# ---------------------------------------------------------------------------

.PHONY: clean
clean: ## Remove build and dependency caches
	rm -rf .cpcache target classes
