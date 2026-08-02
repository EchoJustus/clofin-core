# ADR-0005: Hybrid local and cloud execution

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Technical lead

## Context

CloFin is developed across environments that come and go: cloud development
sessions with quota limits, a personal machine, CI runners. Work must survive the
loss of any one of them. A contributor who can only reach a single laptop —
Windows, macOS or Linux — must be able to clone the repository and have the full
stack running without hunting for undocumented prerequisites.

Cloud sessions add a specific constraint: network egress may be restricted, so
the build cannot assume that every package host is reachable.

## Decision

The repository is the only artefact that matters. Everything needed to run
CloFin is checked in, and the stack is reproducible from a clone with two
prerequisites — **Docker with Compose v2, and GNU Make** — and nothing else.

1. **Containerisation first.** PostgreSQL and every simulated external interface
   are defined in `docker-compose.yml`. `make up` brings the whole stack up.
2. **A unified entrypoint.** `Makefile` is the single command surface:
   `make up`, `make down`, `make test`, `make migrate`, `make run`, `make repl`.
   Targets are thin wrappers over portable commands, never over host-specific
   scripting.
3. **Environment agnosticism.** All configuration comes from environment
   variables with documented defaults in `.env.example`. No absolute paths, no
   cloud-specific services, no assumption of a particular hostname beyond what
   Compose provides.
4. **A local toolchain is optional.** A contributor with the Clojure CLI and JDK
   21 installed gets a faster loop, and the Makefile detects and uses them. A
   contributor without them gets the same behaviour inside a container.
5. **Restricted-egress friendliness.** Runtime dependencies are resolvable from
   Maven Central alone, so the build works in sessions where other package hosts
   are blocked. This is a deliberate constraint on dependency selection, and it
   reinforces [ADR-0004](0004-minimal-dependency-footprint.md).

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Cloud-hosted development environment only** (Codespaces, Gitpod, a dev container tied to one vendor) | Creates exactly the single point of failure this ADR exists to remove, and makes the project unrunnable for a reviewer who just wants to clone it. |
| **A managed database instead of a container** | Requires credentials, an account and network access to evaluate the project. Fatal for an open-source portfolio piece. |
| **Shell scripts instead of a Makefile** | Either two scripts to maintain (`.sh` and `.ps1`) or a hard dependency on one shell. Make is available on macOS and Linux by default and via WSL, Git Bash, Chocolatey or Scoop on Windows. |
| **Task runners** (`just`, `task`, npm scripts) | `just` and `task` need installing before you can run anything. npm scripts would put Node on the critical path of a Clojure project. |
| **Committing a lockfile of vendored jars** | Bloats the repository and hides the dependency graph, defeating the SBOM argument in ADR-0004. |

## Consequences

**Positive**
- A reviewer can go from clone to a running service in one command.
- Work is never trapped in an environment that has expired.
- CI, cloud sessions and laptops execute the same commands, so "works on my machine" has nowhere to hide.

**Negative / accepted cost**
- Docker is a hard prerequisite, which is a real barrier on locked-down corporate machines.
- The Makefile must stay genuinely portable: no GNU-only shell assumptions beyond Make itself, no `docker-compose` v1 syntax.
- Restricting to Maven-Central-resolvable dependencies rules out some good libraries published only to Clojars.

**Risks and how they are mitigated**
- *Risk:* the Makefile silently rots because contributors run raw commands. *Mitigation:* CI invokes the same targets a developer does, so a broken target fails the build.
- *Risk:* container and host toolchains diverge in version. *Mitigation:* the JDK and Clojure versions are pinned in both `Dockerfile` and `deps.edn`.

## Verification

CI runs `make test` and a `make up` / `make health` / `make down` smoke test on a
clean checkout. Any prerequisite beyond Docker and Make breaks that job.
