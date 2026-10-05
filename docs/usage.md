# Using koshchei

This guide covers running, configuring, testing, and driving the episode loop in koshchei from symptom arrival to final resolution. It describes how to operate the worker, the watcher, the control plane, and the developer command-line tools.

## 1. Run it

To run the system, you must have Docker installed. Besides Docker, you need a JVM 17 or newer to run Gradle itself (the build compiles with JDK 21, which the Gradle toolchain provisions through the foojay resolver) and Node.js 18 or newer for the UI. Each long-running command requires its own terminal window. Note that koshchei connects to the Temporal server on `localhost:7233`, which is the same Temporal instance that narrator's worker and picasso share. Because of that shared instance, the Docker Compose file in koshchei does not start Temporal by default, preventing port collisions on port 7233.

Every command in this document is written for bash syntax. In PowerShell, commands such as `./gradlew ...` and `docker compose ...` work as written, including arguments passed with `--args="..."`. To set an environment variable in PowerShell, use `$env:NAME = "value"` instead of `export NAME=value`. Windows PowerShell 5.1 does not support the `&&` chaining operator; place each command on its own separate line or separate consecutive statements with a semicolon `;`. In every shell, quote a path inside `--args` with single quotes (`--args="open --export '<path>' ..."`); without them Gradle splits a path that contains a space. In Git Bash use `$(pwd -W)` rather than `$PWD` to build an absolute path, because the JVM misreads `/c/...`.

```bash
# 1. koshchei's Postgres (host port 15433). Temporal is the shared server on localhost:7233;
#    on a machine where nothing holds 7233 yet, start a private one instead:
#    docker compose --profile temporal up -d
docker compose up -d --wait

# 2. The episode worker (koshchei.host.WorkerKt). It refuses to start without KOSHCHEI_PICASSO.
export KOSHCHEI_PICASSO=mock
./gradlew :host:run

# 3. The control plane (Spring Boot on 127.0.0.1:18190).
./gradlew :api:run

# 4. The Episodes screen (Vite on port 5174, proxies /api -> 127.0.0.1:18190).
cd ui
npm install      # first time only
npm run dev
```

Open `http://localhost:5174` in your browser to view the Episodes screen. The control plane starts even if the database and Temporal are not yet reachable, but any endpoint request that requires either component returns HTTP status 503. Until the worker or the watcher has started once with the owner login, the underlying episode tables do not exist in the database, and the episode list endpoint responds with 503 and the message "episode tables not found".

### Ports

| What | Host port | Started by |
|---|---|---|
| Postgres (database `koshchei`, user `koshchei`) | 15433 | `docker compose up -d` |
| Temporal | 7233 | shared with narrator and picasso; `docker compose --profile temporal up -d` only when nothing holds 7233 |
| Control plane (`:api`) | 18190 | `./gradlew :api:run` |
| Episodes screen (Vite dev server) | 5174 | `npm run dev` in `ui/` |

### Sharing Temporal

koshchei uses the Temporal server on `localhost:7233` in the default namespace, where narrator's worker uses the same server and koshei, the repository koshchei was split from, still contains the same episode loop.

- Both koshchei and koshei name the episode workflow type `EpisodeWorkflow` and its ids `ep:<key>`. `agent-off --all` lists every open workflow of type `EpisodeWorkflow` on the server, so it also sends `agentOff` to koshei's open episodes.
- `open` (and the watcher) use signalWithStart on `ep:<key>`: when a run with that id is still open, the symptom goes to that run, even if koshei started it. The default key is `<runId>:<id>` from the export, so opening the same export line in both repositories gives the same id. The watcher's ids also come from the export and take no prefix, so do not let a koshei watcher and a koshchei watcher read the same exports on one server.
- With `KOSHCHEI_NARRATOR=mock` (the default), the worker registers a Mock narrator on `narrator-tq`. If narrator's real worker also polls that queue, the two compete and Temporal gives each diagnosis to whichever poller takes it. Set `KOSHCHEI_NARRATOR=remote` whenever narrator's worker runs.
- Workflows started by koshchei run on its own queue `koshchei-episode-tq`, so koshei's worker does not run them; the overlap is in the ids, the workflow type and `narrator-tq`.

On a shared server, pass `--key` with a koshchei-specific prefix (the quickstart uses `--key koshchei-demo-1`). For a clean separation, use the private Temporal (`docker compose --profile temporal up -d`) on a machine where nothing else holds 7233.

## 2. Configure

### Environment variables

The table lists all 16 environment variables that koshchei reads across its processes.

| Key | Purpose |
|---|---|
| `KOSHCHEI_PICASSO` | The approval window: `off` (default), `mock` (the test Mock window) or `picasso` (the real approval window). Any other value stops the process at start. The worker and the watcher both refuse `off` (and an unset value); only the worker's value picks the window. Read by the worker and the watcher. Details in [§3](#3-run-the-episode-loop). |
| `KOSHCHEI_PICASSO_URL` | The window's origin, required when `KOSHCHEI_PICASSO=picasso`. Use `http://127.0.0.1:<port>` or `http://localhost:<port>` only, with no path (the client adds `/approvals`), query, fragment or user info. `localhost` must resolve only to loopback addresses with an IPv4 loopback address first, otherwise the start is refused; prefer `http://127.0.0.1:<port>`. Read only by the worker. |
| `KOSHCHEI_PICASSO_AGENT_ID` | The approver id that a POLICY approval sends as AGENT. Required (not blank) when `KOSHCHEI_PICASSO=picasso`. Read only by the worker. |
| `KOSHCHEI_PICASSO_TIMEOUT_MS` | The limit for one request to the window, in ms. Must be a positive number; default 8000. Keep it at most `(dispatchMs − 3000) / 3`, where `dispatchMs` is the policy's `dispatchMs` (30000 in policy v1, so at most 9000). Read only by the worker with `KOSHCHEI_PICASSO=picasso`. |
| `KOSHCHEI_NARRATOR` | Who diagnoses: `mock` (default) or `remote`. Any other value stops the worker at start. With `mock` the worker registers the Mock narrator on `narrator-tq`; with `remote`, narrator's own worker serves that queue. Run only one poller on the queue. Read by the worker. |
| `KOSHCHEI_EPISODE_POLICY` | The episode policy file. Code default `policy/active.yaml`, relative to the working directory; when unset, `:host:run` and `:host:watcher` pass the absolute path of the repository's `policy/active.yaml`. A missing file makes every episode escalate `POLICY_MISSING`; the worker warns at start. Read by the worker and the watcher. |
| `KOSHCHEI_EPISODE_DB_USER` | DB user for the episode tables. Unset or blank: the `KOSHCHEI_DB_USER` / `KOSHCHEI_DB_PASS` login (or its defaults). The URL is always `KOSHCHEI_DB_URL`'s. In production: the runtime role `koshchei_rt` ([§3](#the-episode-tables-and-the-runtime-role)). Read by the worker and the watcher. |
| `KOSHCHEI_EPISODE_DB_PASS` | Password for `KOSHCHEI_EPISODE_DB_USER`. Set both or neither: one without the other is refused at start. Read by the worker and the watcher. |
| `KOSHCHEI_WATCH_EXPORTS` | picasso export directories, separated by the platform path separator (`;` on Windows, `:` elsewhere). Required, with no empty entries. Read only by the watcher. |
| `KOSHCHEI_WATCH_CARRY` | The directory holding picasso's `job-responses.jsonl` (ResultExport schema 1). Optional (not blank when set); when unset, no result reports are carried. Read only by the watcher. |
| `KOSHCHEI_WATCH_INTERVAL_MS` | Poll interval in ms, 100 to 60000; default 2000. Read only by the watcher. |
| `KOSHCHEI_DB_URL` | Postgres JDBC URL. Default `jdbc:postgresql://localhost:15433/koshchei` (the bundled Compose Postgres). Read by the worker, the watcher and the control plane. |
| `KOSHCHEI_DB_USER` | DB user. Default `koshchei`. Read by the worker, the watcher and the control plane. |
| `KOSHCHEI_DB_PASS` | DB password. Default `koshchei`. Read by the worker, the watcher and the control plane. |
| `KOSHCHEI_BIND_ADDRESS` | The address the control plane listens on. Default `127.0.0.1` (loopback only). Read by the control plane. |
| `KOSHCHEI_WORKER_NAME` | The name the worker prints in its log lines. Default `worker-1`. Read by the worker. |

> **Security note.** The control plane has no authentication because it was built as a proof of concept. For that reason, it binds to loopback (`127.0.0.1`) by default, and setting `KOSHCHEI_BIND_ADDRESS` opens the service to other network interfaces. The approval window interface is restricted to loopback connections as well.

The Gradle daemon retains the environment variables of the shell session in which it was first launched. Consequently, any variable exported in a terminal after the daemon is already running does not propagate to a forked task JVM on its own. To resolve this, the Gradle tasks `:host:run`, `:host:watcher`, and `:host:cli` explicitly copy every variable prefixed with `KOSHCHEI_*` from the invoking shell into the forked JVM. `:host:run` and `:host:watcher` also set `KOSHCHEI_EPISODE_POLICY` to the absolute path of `policy/active.yaml` whenever that variable is unset, because their working directory is set to `host/`, where a relative path would fail to find the policy file. The task `:api:run` similarly copies every `KOSHCHEI_*` environment variable from the shell into its own forked JVM.

## 3. Run the episode loop

The episode loop processes an operational fault through its entire lifecycle from initial symptom to resolution. Symptoms arrive from picasso exports, narrator produces an automated diagnosis, a human operator issues an approval decision, koshchei dispatches the approved action to picasso's approval window, and incoming result reports from picasso close out the episode. The full architecture and design are written in Korean in [`docs/design/2026-09-27-episode-outer-loop-design.md`](design/2026-09-27-episode-outer-loop-design.md). Every environment variable referenced in this workflow appears in the configuration table in §2.

1. [Worker modes](#worker-modes): pick the modes.
2. [The policy file](#the-policy-file): check the policy file.
3. [Start it](#start-it): start the processes.
4. [Feed it](#feed-it): feed it symptoms, with the watcher or the dev CLI.
5. [Decide](#decide-the-control-plane-and-the-ui): decide in the UI or through the control plane.
6. [The episode tables and the runtime role](#the-episode-tables-and-the-runtime-role): for production, run the worker and the watcher as the runtime database role.

### Worker modes

The variable `KOSHCHEI_PICASSO` selects the approval window implementation.

| Value | What runs |
|---|---|
| `off` (default) | Nothing: the worker refuses to start, and so does the watcher |
| `mock` | The episode worker on `koshchei-episode-tq` with the test Mock window (`MockPicasso`, in the worker's memory). Remedies reach no robot; the worker prints a warning at start |
| `picasso` | The episode worker with the real window, `HttpApprovalWindow`: `POST /approvals` on `KOSHCHEI_PICASSO_URL` (loopback only), approvals sent as PERSON, or as AGENT under `KOSHCHEI_PICASSO_AGENT_ID` |

The variable `KOSHCHEI_NARRATOR` designates which component handles diagnosis. The default setting `mock` runs an in-process stand-in that selects the first offered remedy candidate other than ESCALATE and supplies a clean diagnosis with citations. Setting this variable to `remote` leaves the Temporal task queue `narrator-tq` to narrator's independent worker process under diagnosis contract 0.6 using the activity `diagnose`.

When running with `KOSHCHEI_PICASSO=picasso`, picasso looks up a declaration for every approver identifier, regardless of whether that identifier designates a PERSON or an AGENT. If an approver identifier has not been declared in picasso beforehand, picasso refuses it with `NOT_DECLARED`.

Each process reads only its own operating environment. The worker process and the watcher process must both have `KOSHCHEI_PICASSO` set to a value other than `off`; only the worker's value picks the window. The control plane process does not inspect this setting. If any process encounters an unrecognized configuration value at startup, it halts execution immediately.

### The policy file

The file `policy/active.yaml` defines policy table v1 under version identifier `2026-10-03.2`. The worker and the watcher evaluate this file by default unless the environment variable `KOSHCHEI_EPISODE_POLICY` points to a different path. The runtime engine re-reads this file from disk before every decision evaluation, so modifications take effect immediately without requiring a process restart. If the policy file is unreadable or rejected during validation, the episode keeps the last valid policy table and suspends autonomous approvals.

- `agentLayerEnabled: true`: enables automated agent processing; switching this setting to `false` directs every episode to hand over to a human operator, and running `agent-off --all` forces open episodes to evaluate and apply the change immediately.
- `autoApprove.APPROVE_REMEDY.allowed: false`: enforces that every remedy requires manual human approval.
- `deadlines`: configures explicit timeout boundaries for every waiting state, including `approvalMs` set to 5 minutes, `evidenceMs` set to 10 minutes, and `episodeMs` set to 1 hour.
- `diagnosis`: configures diagnosis execution to make two attempts of 540 s within a 1,200 s overall timeout, sending a heartbeat signal every 30 s under narrator contract 0.6.
- `correlation`: contains a single rule declaring that a robot's search line and incident line on the same order belong to one shared episode.
- `actionCatalog: []`: specifies that koshchei's own remedies (`SAGA_ACTION`) remain out of scope.

Directly editing this file on disk is the only supported policy management mechanism, as integrating Git versioning with database pointers and dedicated `policy validate` and `activate` tooling is out of scope (design §19).

### Start it

Ensure Postgres is running as shown in §1, export `KOSHCHEI_PICASSO=mock` in the shell for the worker, and start the worker, the control plane, and the UI development server, giving each process its own terminal.

```bash
docker compose up -d --wait                         # as in §1
export KOSHCHEI_PICASSO=mock                        # in the worker's shell (and the watcher's)
./gradlew :host:run                                 # episode worker (+ Mock narrator)
./gradlew :api:run                                  # control plane: /api/episodes…
cd ui && npm run dev                                # the Episodes screen
```

The same steps in PowerShell:

```powershell
docker compose up -d --wait                         # as in §1
$env:KOSHCHEI_PICASSO = "mock"                      # in the worker's shell (and the watcher's)
./gradlew :host:run                                 # episode worker (+ Mock narrator)
./gradlew :api:run                                  # control plane: /api/episodes…
cd ui; npm run dev                                  # the Episodes screen
```

When the worker starts, it logs the database user account it uses to access the episode tables, followed by a log line identifying the episode queue `koshchei-episode-tq`, the active narrator mode, and the loaded policy path. When running with `KOSHCHEI_PICASSO=mock`, it prints an explicit warning stating that dispatched remedies do not reach actual robots. If the target policy file cannot be located on disk, the worker issues a warning that every incoming episode will immediately escalate under the reason `POLICY_MISSING`. If the worker's login may create in the schema (the owner login can), it automatically creates any missing episode tables. Once the worker has initialized, open the Episodes screen in your browser.

### Feed it

Episodes are not opened until external symptoms are delivered to the system. You can deliver symptoms by running the watcher process continuously or by injecting individual symptom lines by hand with the development CLI.

The watcher runs as an independent daemon started with `./gradlew :host:watcher`. It polls export directories generated by picasso and, whenever `KOSHCHEI_WATCH_CARRY` is set, polls picasso's `job-responses.jsonl` file. The watcher waits to process any export bundle until that bundle's `manifest.json` file has appeared. It tracks its polling progress in the database table `episode_watch_cursor`, and writes records that could not be carried forward into `episode_watch_log`. The web UI provides no screen for viewing `episode_watch_log`; you must query the database table directly. The watcher refuses to run if `KOSHCHEI_PICASSO` is `off` or unset. Because the watcher's execution directory is `host/`, always configure directory paths as absolute paths. If a polling pass encounters a failure, the watcher logs the error and retries on the next polling interval.

```bash
export KOSHCHEI_PICASSO=mock
export KOSHCHEI_WATCH_EXPORTS=/abs/path/to/picasso/export
export KOSHCHEI_WATCH_CARRY=/abs/path/to/picasso/carry     # optional
./gradlew :host:watcher
```

The same in PowerShell; on Windows, separate several export directories with `;`:

```powershell
$env:KOSHCHEI_PICASSO = "mock"
$env:KOSHCHEI_WATCH_EXPORTS = "C:\abs\path\to\picasso\export"
$env:KOSHCHEI_WATCH_CARRY = "C:\abs\path\to\picasso\carry"     # optional
./gradlew :host:watcher
```

The development CLI, executed through `./gradlew :host:cli`, opens an individual episode by reading a single picasso export line in the same manner as the watcher. It also provides commands to turn off automated agent handling for a single episode or across all currently open episodes. The CLI communicates exclusively with Temporal and makes no direct database connections. If you provide invalid or malformed arguments, the CLI displays a usage error and terminates without sending signals to Temporal.

```bash
# open: signalWithStart ep:<key> with one line; the default key is <runId>:<id>
./gradlew :host:cli --args="open --export '/abs/path/to/export' --search search-1 --key koshchei-demo-1"
./gradlew :host:cli --args="open --export '/abs/path/to/export' --incident incident-1 --key koshchei-demo-2"

# agent-off: one episode, or every open one; the episode goes to a person (AGENT_LAYER_OFF)
./gradlew :host:cli --args="agent-off ep:koshchei-demo-2"
./gradlew :host:cli --args="agent-off --all"
```

The commands run unchanged in PowerShell; give a Windows absolute path there, still inside single quotes. The examples pass `--key` with a koshchei-specific prefix; see ["Sharing Temporal"](#sharing-temporal) in §1. The repository includes sample export files located at `runtime/src/test/resources/picasso/run-1`, containing four search entries and nine incident records. When referencing these sample files, supply an absolute filesystem path, because `:host:cli` runs with `host/` as its working directory.

### Decide: the control plane and the UI

The `:api` process hosts the control plane on port 18190, binding to loopback by default. It reads persisted state directly from the episode database tables and transmits operator decisions to running Temporal workflows using Workflow Updates. The core decides; a refusal comes back as a value.

| Route | What |
|---|---|
| `GET /api/episodes?limit=` | episode instances (`limit` 1..500, default 50) |
| `GET /api/episodes/notices?after=&limit=` | the notice feed, in id order (`limit` 1..500, default 100) |
| `GET /api/episodes/{workflowId}/{run}` | records, notices, and while the run is live its view and operator card |
| `POST /api/episodes/{workflowId}/{run}/decide` | approve or reject a proposal |
| `POST /api/episodes/{workflowId}/{run}/confirm` | confirm a precondition, an outcome or an unknown |
| `POST /api/episodes/{workflowId}/{run}/takeover` | a person takes the episode over |
| `POST /api/episodes/{workflowId}/{run}/close` | close an escalated episode |

All `POST` endpoints require the operator's identity in an `X-Koshchei-Operator` HTTP request header, with a maximum length of 128 characters. Request payloads must be valid JSON; the endpoints reject unexpected or duplicate JSON fields, and payloads exceeding 64 KiB receive an HTTP 413 response. The control plane implements no authentication mechanism (see the security note in §2). The web interface in `ui/` presents the episode list view, the detail panel (showing the operator card, the four decision controls, history, candidates, and records), and the live notice feed. The interface stores the active operator name locally in the browser under `koshchei.operator`.

A refusal by the core is still a 200 with the refusal in `reply`; only the statuses in the table mean the request did not reach the episode or its answer is unknown.

| Status | When | Body |
|---|---|---|
| 200 | The request reached the episode and it answered, a refusal included | `{"reply": "ACCEPTED"}`, or a refusal such as `{"reply": "REFUSED_STALE"}` |
| 400 | The operator header is missing or over 128 characters; the body is not one strict JSON object (an unknown or repeated field, trailing content, a number or a string where `true`/`false` belongs); a required field is missing; the Update's validator refuses the request's form; `limit` is outside 1..500 | `{"error": "…"}` |
| 404 | No such episode workflow; a `GET` of an instance with nothing live and nothing recorded | `{"error": "…"}` |
| 409 | The instance is not the workflow's current run; nothing is sent | `{"error": "EPISODE_MOVED", "currentInstanceId": "…"}` |
| 409 | The run has ended | `{"error": "EPISODE_ENDED"}` |
| 413 | The body is over 64 KiB | `{"error": "…"}` |
| 503 | Temporal is unreachable, no worker answers the check before a decision, the database is unreachable, or the episode tables do not exist yet | `{"error": "…"}` |
| 504 | The Update got no answer within 30 s; it may still be applied. Read the episode again before deciding again | `{"error": "EPISODE_TIMEOUT", "outcome": "UNKNOWN", "instanceId": "…"}` |

`takeover` takes no body; unknown or repeated fields are refused.

| Route | Body (one JSON object) |
|---|---|
| `decide` | `proposalId` and `sawCandidatesVersion` (required); `approve` (`true` or `false`, required); `reason` (optional, for a rejection: `WRONG_TARGET`, `WRONG_ACTION`, `PRECONDITION_NOT_MET`, `NOT_NOW`, `OTHER`); `note` (optional) |
| `confirm` | `kind`: `PRECONDITION` or `OUTCOME` (with `candidateId` and `proposalId`), or `UNKNOWN` (with `subject`, an object, and `what`: `OBSERVATION_ABSENT`, `LINK_BROKEN`, `LATE_EVENTS`, `PROGRESS_UNOBSERVED`, `PROGRESS_STALLED` or `OUTCOME`); `holds` (`true` or `false`, required); `note` (optional). There is no proposition field: the control plane takes the proposition from its own card |
| `close` | `outcome` (required, free text) |
| `takeover` | none |

`proposalId` and `candidatesVersion` come from the `view` in `GET /api/episodes/{workflowId}/{run}`; the body field is `sawCandidatesVersion`.

```bash
curl -s -X POST "http://127.0.0.1:18190/api/episodes/ep:koshchei-demo-1/<run>/decide" \
  -H "X-Koshchei-Operator: alice" -H "Content-Type: application/json" \
  -d '{"proposalId": "<view.proposalId>", "sawCandidatesVersion": "<view.candidatesVersion>", "approve": true}'
```

When operating against picasso with `KOSHCHEI_PICASSO=picasso`, precondition revalidation always returns UNKNOWN. Because of this, every dispatch operation pauses and waits for a human operator to inspect and confirm the precondition displayed on the card (`confirm` on the precondition) before the dispatch proceeds.

### The episode tables and the runtime role

When started using the database owner login credentials, either the worker or the watcher automatically initializes the five core database tables defined in `runtime/src/main/resources/episode-schema.sql`: `episode_event`, `episode_notice`, `episode_dispatch`, `episode_watch_cursor`, and `episode_watch_log`. The control plane accesses these database tables using the database owner account supplied in `KOSHCHEI_DB_*`. Configuring a separate, restricted read-only account for the control plane is currently out of scope. In production environments, the worker and the watcher should not connect as the database owner, but should instead run under the restricted runtime role `koshchei_rt` defined in `scripts/db-roles.sql`. That setup script restricts permissions by granting only `SELECT` and `INSERT` privileges on the five episode tables and `USAGE` and `SELECT` on their two sequences, and it aborts execution with an error if the role possesses `CREATE` privileges in the schema. When their login may not create in the schema (as `koshchei_rt` may not), the worker and the watcher only verify that the tables exist and do not attempt to run schema creation statements. Consequently, the schema must be applied beforehand: either execute `episode-schema.sql` directly using the database owner account or start the worker once without `KOSHCHEI_EPISODE_DB_USER` configured. The owner account runs the role setup script once:

```bash
# psql on the host (against the bundled Compose Postgres, add: -h localhost -p 15433)
psql -v ON_ERROR_STOP=1 -v rt_password='…' -U koshchei -d koshchei -f scripts/db-roles.sql

# or through Compose, with no psql on the host
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -v rt_password='…' -U koshchei -d koshchei < scripts/db-roles.sql
```

```powershell
# the Compose route in PowerShell (no < redirection there)
Get-Content scripts/db-roles.sql -Raw | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -v "rt_password=…" -U koshchei -d koshchei
```

The script itself sets the role's password from the psql variable `rt_password`, so pass a throwaway value there. Then replace it: open an interactive psql as the owner (`docker compose exec postgres psql -U koshchei -d koshchei`, or psql on the host) and run `\password koshchei_rt`, which prompts for the password and keeps it out of shell history and process lists. Finally, configure `KOSHCHEI_EPISODE_DB_USER` and `KOSHCHEI_EPISODE_DB_PASS` in the shell environments of both the worker and the watcher.

## 4. Test it

```bash
./gradlew test          # 705 tests: core 323, runtime 311 (1 skipped), host 29, api 42

cd ui
npm install             # first time only
npm test                # Vitest: 41 tests
npm run test:e2e        # Playwright: a warmup step, then 20 tests (stop npm run dev first)
```

Executing `./gradlew test` runs the suite of 705 tests, completing with 0 failures and 1 test skipped. The single skipped test is the generator of the committed workflow replay histories; it executes only when explicitly requested by passing `-Dkoshchei.writeReplayHistories=true`. The runtime test suite verifies workflow deterministic replay against nine committed workflow histories located in `runtime/src/test/resources/replay/2026-10-04/`. All database tests use Testcontainers with a `postgres:16` image, which requires a functioning local Docker daemon. The frontend end-to-end test suite executes against the Vite development server on port 5174 and intercepts `/api/episodes` within the browser context, allowing UI tests to run without starting a backend process. There are currently no end-to-end tests that execute against a live backend stack.

Stop `npm run dev` before `npm run test:e2e`: Playwright starts its own Vite dev server on port 5174 and refuses to reuse one that is already running. The tests run in the installed Google Chrome (Playwright's `chrome` channel), so Google Chrome must be installed.

The generator runs only when asked, and only for a deliberate workflow change guarded by `Workflow.getVersion`; it refuses a set directory that already exists. In Windows PowerShell 5.1, quote each `-D` argument (`"-Dkoshchei.writeReplayHistories=true"`), or PowerShell splits it; bash needs no quotes.

```bash
./gradlew :runtime:test --tests "*CommittedReplayTest*" -Dkoshchei.writeReplayHistories=true -Dkoshchei.replaySet=<name> --rerun
```

```powershell
./gradlew :runtime:test --tests "*CommittedReplayTest*" "-Dkoshchei.writeReplayHistories=true" "-Dkoshchei.replaySet=<name>" --rerun
```

## 5. Limits & license

koshchei is distributed under the terms of the PolyForm Noncommercial License 1.0.0 ([`LICENSE.md`](../LICENSE.md)). The project is source-available and permitted exclusively for noncommercial applications; commercial use is reserved. The project is not licensed under an OSI-approved open-source license.

- The implementation is a proof of concept intended for single-machine deployment.
- There is no real picasso host implementation available yet (a dedicated deployment repository is planned; picasso's reference host serves as the testing peer), and the approval window binds only to loopback interfaces, requiring koshchei to reside on the same host machine as picasso.
- When running with the real approval window, precondition revalidation always yields UNKNOWN, requiring a human operator to verify and confirm every precondition manually.
- The precise definition of the DONE state—specifically whether it indicates that the individual remedy has completed or that the entire underlying order has resolved—remains an open design decision (design §19 F).
- The control plane contains no authentication layer and queries the episode tables using the privileged database owner account.

## See also

- [`README.md`](../README.md) — what koshchei is, where it came from, the quickstart.
- [`docs/design/2026-09-27-episode-outer-loop-design.md`](design/2026-09-27-episode-outer-loop-design.md) — the design, in Korean; implementation log in §16, open decisions in §19.
- [`docs/plans/`](plans/) — the implementation plans, in Korean.
