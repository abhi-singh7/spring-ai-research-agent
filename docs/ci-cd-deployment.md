# CI/CD on GitHub Actions → Local Ubuntu Deployment

This document explains, line by line, how this project's continuous
integration and deployment pipeline works: what runs where, why each piece
exists, and how to operate it day-to-day. Written as a learning reference for
GitHub Actions concepts.

```
 ┌────────────┐   push/PR    ┌─────────────────────────┐
 │  GitHub    │ ───────────► │ "Research Agent CI"     │  (GitHub-hosted runners)
 │  repo main │              │  backend-build          │   • mvn clean verify
 └────────────┘              │  frontend-build         │   • npm ci + build
                             └───────────┬─────────────┘
                                         │ workflow_run: success
                                         ▼
                             ┌─────────────────────────┐
                             │ "Deploy to local Ubuntu"│  (SELF-HOSTED runner =
                             │  deploy job             │   your Ubuntu machine)
                             └───────────┬─────────────┘
                                         │ npm build → copy static/ → mvn package
                                         ▼
                             /home/abhi/research-agent-deploy/app.jar
                                         │ systemctl --user restart research-agent
                                         ▼
                              http://localhost:8080  (API + UI)
```

---

## 1. GitHub Actions core vocabulary (used throughout)

| Term | Meaning |
|------|---------|
| **Workflow** | A YAML file in `.github/workflows/`. Each file = one workflow. Ours: `ci.yml`, `deploy.yml`. |
| **Trigger (`on:`)** | Events that start a workflow: `push`, `pull_request`, `workflow_run` (another workflow finished), `workflow_dispatch` (manual button). |
| **Job** | A block under `jobs:`. Each job runs on its own runner VM/machine and gets a fresh environment (unless self-hosted, where the machine persists). Jobs in the same workflow run **in parallel** unless ordered with `needs:`. |
| **Step** | One command (`run:`) or one reusable action (`uses:`) inside a job. Steps run sequentially in one shell session. |
| **Runner** | The machine that executes jobs. `ubuntu-latest` = an ephemeral GitHub-hosted VM (destroyed after the job). A **self-hosted** runner is a daemon on your own machine that polls GitHub for work. |
| **Action** | Reusable step: official (`actions/checkout@v4`) or third-party. Pinned by major version tag so updates are opt-in. |
| **Contexts / expressions** | `github.sha`, `github.event_name`, `${{ ... }}` — dynamic values injected at runtime. |
| **Service container** | A Docker container (e.g. MongoDB) started alongside a job's container, with ports published into the job so tests can reach it on `localhost`. Stopped automatically after the job. |
| **Concurrency group** | Ensures only one run of a workflow "group" executes at a time; others queue. |
| **Secrets** | Encrypted values (Settings → Secrets). Not needed here — see why in §4. |

---

## 2. CI workflow — `.github/workflows/ci.yml`, line by line

```yaml
name: Research Agent CI
```
Display name shown in the Actions tab and as the PR check name. **Important:**
`deploy.yml` refers to this exact string in its `workflow_run` trigger — rename
here and you must update `deploy.yml` too.

```yaml
on:
  push:
    branches:
      - main
  pull_request:
    branches:
      - main
```
Two triggers:
- `push` to `main` → runs after every merge/landed commit (this is the run that
  later triggers deployment).
- `pull_request` targeting `main` → runs on every PR push, giving green/red
  checks *before* merging.

Both jobs below must succeed for the PR check suite to be green.

```yaml
jobs:

  backend-build:
    runs-on: ubuntu-latest
```
A job named `backend-build`, executed on a GitHub-hosted Ubuntu VM. The VM is
created fresh per run, deleted afterwards — nothing persists (which is exactly
why we need a service container for MongoDB below).

```yaml
    services:
      mongodb:
        image: mongo:7
        ports:
          - 27017:27017
        options: >-
          --health-cmd "mongosh --eval 'db.adminCommand({ping:1})'"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
```
**Why this exists:** two `@SpringBootTest` integration tests
(`OrchestratorPersistenceIntegrationTest`, `AbandonedSessionCleanupServiceTest`)
require a *real* MongoDB at `mongodb://localhost:27017/research-agent-test`
(configured in `research-agent-backend/src/test/resources/application.yml`).
GitHub's VMs have no Mongo, so the job started one as a **service container**:

- `image: mongo:7` — official MongoDB 7 Docker image.
- `ports: - 27017:27017` — publishes the container's port 27017 onto the
  job container's localhost, so `localhost:27017` from Maven tests resolves to
  Mongo. (Format is `host:container`.)
- `options:` — extra docker-run flags. The health check makes GitHub **wait
  until Mongo actually answers** before starting the job steps; without it the
  first test could race Mongo's startup and fail flakily.
  - `--health-cmd "mongosh --eval 'db.adminCommand({ping:1})'"` — command run
    *inside* the Mongo container; exits 0 only when the server responds to a
    ping. (Note: `db.adminCommand(1)` is invalid in mongosh — the object form
    `{ping:1}` is required. This exact bug broke our first CI attempt.)
  - `--health-interval 10s` / `--health-timeout 5s` / `--health-retries 5` —
    probe every 10 s, give up on a probe after 5 s, fail the service after 5
    consecutive failed probes (≈ up to 50 s grace).

```yaml
    steps:
      - name: Checkout source
        uses: actions/checkout@v4
```
Clones the repo at `github.sha` into the workspace (`GITHUB_WORKSPACE`). Every
job that needs code starts with this.

```yaml
      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
```
Installs Temurin JDK 21 (matching the project's toolchain) and puts `mvn` on
PATH. `cache: maven` restores/stores the local Maven repository keyed by
`pom.xml` — dependencies are downloaded once per dependency-set, cutting build
time substantially on later runs.

```yaml
      - name: Build backend
        working-directory: research-agent-backend
        run: mvn clean verify
```
Runs in the `research-agent-backend/` subdirectory (steps otherwise run at the
repo root). `mvn clean verify`:
- `clean` — delete previous `target/`;
- `verify` — full lifecycle: compile → **run all tests** (including the two
  Mongo integration tests) → package the jar.
This single command is the backend's quality gate.

```yaml
  frontend-build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout source
        uses: actions/checkout@v4

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: research-agent-ui/package-lock.json
```
Second job — runs **in parallel** with `backend-build` (no `needs:`), on its own
fresh VM. Installs Node 20 and enables the npm cache, keyed by the lockfile at
`cache-dependency-path` (the file whose hash identifies the dependency set).

```yaml
      - name: Install dependencies
        working-directory: research-agent-ui
        run: npm ci
```
`npm ci` = clean install **strictly from `package-lock.json`** (fails if the
lockfile and `package.json` disagree). Unlike `npm install`, it never modifies
the lockfile — deterministic, reproducible builds.

```yaml
      - name: Build Angular application
        working-directory: research-agent-ui
        run: npm run build
```
Production build of the Angular app → output in
`research-agent-ui/dist/research-agent-ui/browser/` (index.html + hashed JS/CSS
chunks). CI only verifies it *compiles*; the actual serving happens at deploy
time (§3).

**Result:** PRs and pushes to `main` get a green/red "Research Agent CI" check
covering both halves of the stack, with a real database for the integration
tests.

---

## 3. The self-hosted runner (the "local Ubuntu" half)

### Why a self-hosted runner instead of SSH from GitHub's cloud?

The app only works where its dependencies live: local MongoDB (`:27017`),
local Ollama LLM endpoint, local Firecrawl (`:3002`). A GitHub-hosted runner is
a VM in *GitHub's* cloud — it can build the jar but cannot reach your
localhost services, and "deploy to my machine" from there would require storing
an SSH private key + host in GitHub Secrets.

A **self-hosted runner** flips that: a small daemon installed *on your Ubuntu
machine* polls GitHub for jobs labelled for it. The deploy job then executes
**directly on your machine** — deploying is just "copy the jar, restart the
service". No SSH, no secrets, and the deployed app can talk to your local Mongo/LLM/Firecrawl normally.

Trade-off (know this!): whatever Actions code runs in your repo now executes on
your machine with your user's privileges. That's acceptable for a private,
single-user repo — not for public repos with forkable workflows.

### How it was installed (reproducible steps)

1. **Download** the latest runner release for linux-x64
   (`actions-runner-linux-x64-<ver>.tar.gz` from `github.com/actions/runner`
   releases) and extract to `/home/abhi/actions-runner`.
2. **Get a registration token.** The old
   `POST /repos/{owner}/{repo}/actions/runners/registration` endpoint is gone;
   the current flow issues a *short-lived* token (≈1 h expiry):
   ```bash
   curl -X POST -H "Authorization: Bearer <PAT>" \
     https://api.github.com/repos/abhi-singh7/spring-ai-research-agent/actions/runners/registration-token
   # → {"token": "AQA...", "expires_at": "..."}
   ```
   Using a throwaway token instead of your long-lived PAT is safer — the PAT
   never touches the runner machine.
3. **Configure** the runner:
   ```bash
   cd /home/abhi/actions-runner
   ./config.sh --url https://github.com/abhi-singh7/spring-ai-research-agent \
               --token <registration-token> \
               --label ubuntu-local --name abhi-ubuntu
   ```
   - `--name abhi-ubuntu` — display name in the Actions → Runners UI.
   - `--label ubuntu-local` — a custom label; jobs select runners by labels via
     `runs-on`. (Every runner auto-gets `self-hosted`, `Linux`, `X64` too.)
   - If a runner with the same name already exists, add `--replace`.
   - Config writes `.runner` (URL + auth) and creates the `_work/` folder where
     job checkouts land.
4. **Run it as a systemd user service** (no sudo needed on this box):
   `~/.config/systemd/user/actions-runner.service`:
   ```ini
   [Unit]
   Description=GitHub Actions self-hosted runner (abhi-ubuntu)
   After=network-online.target
   Wants=network-online.target

   [Service]
   Type=simple
   WorkingDirectory=/home/abhi/actions-runner
   ExecStart=/home/abhi/actions-runner/run.sh
   Restart=on-failure
   RestartSec=10

   [Install]
   WantedBy=default.target
   ```
   - `Type=simple` — systemd considers it started when `run.sh` is spawned.
   - `Restart=on-failure` + `RestartSec=10` — auto-recovery if the daemon dies.
   - User services run under your user and die at logout **unless linger is on**:
     `loginctl show-user abhi | grep Linger` → `Linger=yes` (already set), so both
     services survive logout and start at boot after `enable`.

### Runner state

| Item | Value |
|------|-------|
| Install dir | `/home/abhi/actions-runner` (v2.337.0) |
| Work folder | `/home/abhi/actions-runner/_work/<repo>/<repo>` (fresh per job) |
| Name / labels | `abhi-ubuntu` — `self-hosted, Linux, X64, ubuntu-local` |
| Service | `systemctl --user status actions-runner` |
| Diagnostics | `~/actions-runner/diag` dumps logs; `./svc.sh` manages the service if you ever switch to system-level |

---

## 4. Deploy workflow — `.github/workflows/deploy.yml`, line by line

```yaml
name: Deploy to local Ubuntu
```
Again: this string is only cosmetic here, but keep it stable and descriptive.

```yaml
on:
  workflow_run:
    workflows: ["Research Agent CI"]
    types: [completed]
    branches: [main]
```
**The key trigger for "deploy after CI".** `workflow_run` fires when *another*
workflow in the same repo finishes:
- `workflows:` — only runs of "Research Agent CI" (matched by that exact name).
- `types: [completed]` — fire when it reaches a terminal state (success **or**
  failure — hence the guard below).
- `branches: [main]` — only CI runs whose base branch is `main`, so PR runs
  never trigger deploys.

Why not just `on: push: branches: [main]`? That would start the deploy *in
parallel* with CI — racing it, and deploying code that may then fail tests.
`workflow_run` gives a hard ordering guarantee: **deploy only ever sees a commit
CI already validated.**

```yaml
  workflow_dispatch:
    inputs:
      ref:
        description: Branch or commit to deploy
        required: false
        default: main
```
Adds the manual "Run workflow" button in the Actions tab, with an optional
`ref` input (branch/commit) — used for one-off deploys of feature branches and
for testing the pipeline without touching `main`.

```yaml
concurrency:
  group: deploy-local-ubuntu
  cancel-in-progress: false
```
Only one deploy runs at a time on this machine. If a second one is triggered
while the first is active, it **queues** (`cancel-in-progress: false`) instead
of cancelling or racing — two jobs copying the same jar / restarting the same
service concurrently would be chaos.

```yaml
jobs:
  deploy:
    if: github.event_name == 'workflow_dispatch' || github.event.workflow_run.conclusion == 'success'
```
Guard clause, evaluated per run:
- Manual dispatch → always proceed (you asked for it explicitly).
- `workflow_run` trigger → proceed **only if the CI run concluded `success`**.
  (`conclusion` can be `failure`, `cancelled`, `skipped` — all blocked.)

```yaml
    runs-on: [self-hosted, linux, ubuntu-local]
```
Label matching: the job is offered to runners that have **all** listed labels.
This uniquely selects your machine. (With only one runner, `[self-hosted, linux]`
would also work; the custom label future-proofs it if you add more.)

```yaml
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          ref: ${{ github.event_name == 'workflow_dispatch' && github.event.inputs.ref || github.sha }}
```
An inline expression (ternary):
- Manual dispatch → checkout `github.event.inputs.ref` (your input, default
  `main`).
- Otherwise → `github.sha`, which for a `workflow_run` event is **the head SHA of
  the CI run that just completed** — i.e. exactly the commit CI validated.

```yaml
      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
```
Same as CI. On a self-hosted runner the tool install and Maven cache persist on
your machine between runs, so this is fast after the first run.

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: research-agent-ui/package-lock.json
```
Pins Node 20 (same as CI) regardless of whatever Node your desktop happens to
have — reproducible builds.

```yaml
      - name: Build frontend
        working-directory: research-agent-ui
        run: npm ci && npm run build
```
Clean install + production Angular build → `dist/research-agent-ui/browser/`.

```yaml
      - name: Copy frontend into backend static resources
        run: |
          mkdir -p research-agent-backend/src/main/resources/static
          cp -r research-agent-ui/dist/research-agent-ui/browser/* \
            research-agent-backend/src/main/resources/static/
```
**The packaging trick that makes one jar serve everything.** Spring Boot serves
anything under `src/main/resources/static/` from the classpath at `/`. By
copying the Angular build there *before* `mvn package`, the UI is baked into the
jar — one process, one port (`:8080`), no separate web server or `ng serve`.
(The prebuilt assets are gitignored; they're generated fresh on every deploy.)

```yaml
      - name: Build backend jar (tests already verified by CI)
        working-directory: research-agent-backend
        run: mvn -DskipTests package
```
`package` produces `target/research-agent-0.0.1-SNAPSHOT.jar`. `-DskipTests`
skips the test phase — tests already ran in CI for this exact commit, so
re-running them here would only double the time. (If you ever deploy a commit
that *didn't* go through CI — e.g. an old branch via manual dispatch — drop the
flag.)

```yaml
      - name: Install and restart service
        run: |
          mkdir -p /home/abhi/research-agent-deploy
          cp research-agent-backend/target/research-agent-0.0.1-SNAPSHOT.jar \
            /home/abhi/research-agent-deploy/app.jar
          systemctl --user restart research-agent
```
The actual deployment, three lines:
1. Ensure the deploy directory exists (first run only).
2. Replace `app.jar` with the freshly built one (atomic enough for our purposes;
   the old process keeps its already-loaded classes until restart).
3. `systemctl --user restart research-agent` — stops the old JVM, starts the new
   jar under systemd supervision. Works from the runner because the runner
   daemon itself is a user service, so jobs inherit `XDG_RUNTIME_DIR` / the
   session bus address needed to talk to your user's systemd instance.

```yaml
      - name: Verify service is up
        run: |
          for i in $(seq 1 30); do
            # /api/health is the unauthenticated liveness probe — every /api/research/**
            # endpoint requires a Bearer JWT since the auth workstream, so a 401 there
            # would read as "down" to curl -f even though the app is up.
            if curl -sf http://localhost:8080/api/health > /dev/null; then
              echo "Research Agent is up on :8080"
              exit 0
            fi
            sleep 5
          done
          echo "::error::Service did not come up in time"
          journalctl --user -u research-agent --no-pager -n 40
          exit 1
```
A startup health gate: poll the real API endpoint for up to 30 × 5 s = 150 s.
- Success → step (and the whole run) is green.
- Failure → `::error::` marks a prominent error line in the log, then the last 40
  journal lines are dumped so you can see *why* (port conflict, Mongo down, bad
  config…) without SSHing anywhere, and `exit 1` turns the run red.

---

## 5. The app service on the Ubuntu machine

`~/.config/systemd/user/research-agent.service`:

```ini
[Unit]
Description=Research Agent backend (Spring Boot jar)
After=network-online.target mongod.service

[Service]
Type=simple
WorkingDirectory=/home/abhi/research-agent-deploy
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xmx1g -jar /home/abhi/research-agent-deploy/app.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

- `After=network-online.target mongod.service` — order the app start *after*
  network is up and after the system-level `mongod.service`, so Mongo is
  available at boot. (Ordering ≠ guarantee, but it removes the common race.)
- `ExecStart` — absolute JDK path (no `PATH` surprises under systemd),
  `-Xmx1g` heap cap for a single-user app.
- `Restart=on-failure` / `RestartSec=5` — crash → auto-restart after 5 s.
- Linger is enabled, so it starts at boot and survives logout.

Config comes from the jar's `application.yml` (Mongo URI, LLM endpoint, Firecrawl
base URL, timeouts) — all with local defaults, so no environment files are
needed right now. Add an `EnvironmentFile=` line if you ever externalize secrets.

---

## 6. What actually happened on a real run (2026-09-28)

1. Commit `1225c45c` pushed to `main`.
2. **CI run** `36420061267` started: two parallel jobs on GitHub-hosted VMs;
   `backend-build` spun up the `mongo:7` service container, waited for its
   health check, ran 124 tests (0 failures); `frontend-build` compiled Angular.
   → `success`.
3. The `workflow_run` trigger fired **"Deploy to local Ubuntu"** run
   `36420191284`; the guard saw `conclusion == success`; the job matched the
   `ubuntu-local` label and executed **on this machine**.
4. Steps: checkout `1225c45c` → Node 20 + npm build → copy into `static/` →
   `mvn -DskipTests package` → jar copied to `/home/abhi/research-agent-deploy/app.jar`
   → `systemctl --user restart research-agent`.
5. Health gate polled `:8080/api/health` (unauthenticated liveness probe —
   `/api/research/**` requires a Bearer JWT since the auth workstream), got 200 → run green.
6. App now served by systemd (active since 17:41 IST), UI + API on :8080.

---

## 7. Day-2 operations cheat sheet

```bash
# Watch a run / read logs
gh run list --limit 5
gh run view <run-id> --log            # or Actions tab in browser
gh workflow run "Deploy to local Ubuntu" -f ref=main    # manual deploy

# App service
systemctl --user status research-agent
journalctl --user -u research-agent -n 50 --no-pager     # recent logs
systemctl --user restart|stop|start research-agent

# Runner
systemctl --user status actions-runner
~/actions-runner/diag                # runner diagnostics bundle
gh api repos/abhi-singh7/spring-ai-research-agent/actions/runners   # online?

# Smoke tests
curl -s http://localhost:8080/api/health            # unauthenticated liveness probe
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/
```

Useful GitHub UI spots: **Actions → Runners** (runner health/labels),
**Settings → Secrets and variables → Actions** (if you ever add secrets),
**Settings → Actions → General** (workflow permissions).

---

## 8. Security notes

- The self-hosted runner executes workflow code as user `abhi` — keep the repo
  private / single-user, or restrict who can push.
- The GitHub PAT used for setup is stored in `/home/abhi/.bashrc` and
  `/home/abhi/.git-credentials` (chmod 600). **Rotate it** if this chat or these
  files were ever shared; update both locations after rotating.
- Runner registration uses short-lived tokens by design; the PAT itself never
  lives on the runner.
