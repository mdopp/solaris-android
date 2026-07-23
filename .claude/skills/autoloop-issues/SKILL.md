---
name: autoloop-issues
description: Orchestrates an autonomous issue-resolution pipeline for mdopp/solaris-android — Planner → Builder → Verify — coordinated through a shared work queue, spawning each stage as a fresh sub-agent so the loop session stays clean. Adapted from the solarisbay autoloop for a native Android/Gradle app: "verify" is Gradle build + Robolectric tests + a signed release APK (there is no server/box to deploy to — on-device behaviour is human-gated by sideloading the APK). Cross-repo needs become tickets in solarisbay/servicebay, never built here. Signing-key / device-token-contract changes open as a DRAFT PR for human review. Core state lives in GitHub (autoloop:* labels/issues/PRs); a tiny gitignored cache holds only in-flight run state, brokered by queue.py and rebuildable from GitHub. Use when the user asks to "burn down the android backlog", "work the solaris-android issues autonomously", or invokes /loop with this skill.
---

# Autoloop orchestrator — mdopp/solaris-android

You are the **coordinator** of an autonomous issue-resolution pipeline for the
**native Android app**. You do **not** write code or groom issues yourself — you
run a tight dispatch loop that **spawns a fresh sub-agent per stage** and routes
coordinate through the `queue.py` state broker: durable state in GitHub (`autoloop:*` labels), a tiny gitignored local cache for in-flight run state.

```
   you (orchestrator) → preflight (queue.py summary) → dispatch ONE stage → re-read → cadence
 PLANNER ──queue.py plan──▶ BUILDER ──claim/built/seal → CI → merge──▶ VERIFY ──▶ release(APK)
 groom/cluster/route          fast gates per issue,          build + Robolectric +
 cross-repo → tickets         8/batch on batch/<id>          signed APK; device = human
```

Each sub-agent starts cold and returns one line, so the long-lived loop stays
small. **Read the project `CLAUDE.md` and user memory before the first iteration
— they override this skill on conflict** (esp. Conventional Commits, "never
commit the keystore", cross-repo=ticket, German replies).

## What solaris-android is (and why verify is unusual)
It's a **standalone native Android app** (Kotlin + Gradle, no Bubblewrap) — a
widgets/onboarding shell that pairs to a user's own Solaris server and talks to
the token-only `/napi/` prefix. It is **not** deployed to a server. So there is
**no real-box `/verify`**: the closest thing to "the box" is the **user's phone**,
and installing the APK there is **human-gated**. The pipeline therefore verifies
what it *can* headlessly — **it compiles, it passes Robolectric tests, it produces
a correctly-signed release APK** — and leaves on-device confirmation to the human
(who sideloads the APK). The in-app crash reporter (`SolarisApp` →
`last_crash.txt`) turns any device crash into a copyable report, so device bugs
come back as precise traces, not mysteries.

## State — GitHub is the source of truth; `queue.py` brokers a tiny local cache
No stage loads a big JSON blob into context — every stage calls `queue.py` verbs and gets
only the slice it needs. State splits by durability:

**DURABLE / core → GitHub (source of truth).** Issue open/closed, work status as `autoloop:*`
labels, human questions/links as issue comments, completion as closed-issue + merged-PR.
Survives firings, machines, and **concurrent instances** — the `autoloop:building` label is
the **cross-instance claim**, so two instances never grab the same issue. Labels: `queued`
(planned) · `building` (claimed) · `blocked` · `needs-refinement` · `review` (security
draft-PR — never auto-merge) · `device-test` (sideload + confirm on the phone) ·
`upstream-wait` (blocked on an unmerged **solarisbay/servicebay** ticket — cross-repo =
ticket only) · `verify-pending`/`verify-failed` (on the release PR).

**EPHEMERAL run state → `.claude/state/autoloop-cache.json`** (**gitignored**, touched only
via `queue.py`): the in-flight `batch`, this run's unit **plan** (`{id, kind, issues[],
theme, region, scope, acceptance, gate, security, status, pr}`), the `verify` state-machine
(`owed→verifying→green|red`; here "verify" = build + tests + signed APK), and a bounded
`notes` ring. A few KB, never committed, rebuildable from GitHub (`queue.py rebuild`).

`queue.py` enforces caps, pruning, one-way label projection, and the cross-instance claim
**in code**. `security:true` on a unit → a **draft** PR + `autoloop:review` label (never
auto-merged), the app's pre-merge review path.

### `queue.py` verbs — the only way stages touch state
`python3 .claude/skills/autoloop-issues/queue.py <verb>` (`--offline` skips gh; covered by `selftest`):
`summary` (orchestrator peek) · `candidates` / `plan` / `park <issue> <blocked|refinement|review|device-test|upstream-wait>` / `note` (planner) · `next` / `claim` / `built` / `batch new|seal|reset` / `verify-set <sha> <status> [--pr N]` (builder) · `verify-get` / `mirror` / `rebuild` / `lock` (orchestrator).

## Batch economy — the prime directive (ENFORCED)
The expensive tail — full test suite, CI, a signed release build — runs **once
per batch (up to 8 closed issues), never per issue.** Fixes accumulate on ONE
branch `batch/<id>`; it is pushed / PR'd / CI'd / merged **only when it holds 8
closed issues OR the planned queue is empty.** Shipping one issue as its own PR
while planned units remain is a failure of this pipeline. The builder enforces the
per-issue side (fast compile gate, commit to batch, no push); **you** enforce the
batch side: never dispatch a seal step while `batch.count < 8` AND planned units
remain. Build-ahead is allowed; seal-ahead is not.

## Where human attention goes (the whole point)
1. Drain `needs_refinement[]` — sharpen ambiguous issues.
2. Review `review[]` — the signing/token-contract **draft** PRs (never auto-merge).
3. Work `device_test[]` — sideload the built APK and confirm behaviour on the phone.
Everything else — grouping, building, compile/test gates, signing — runs without you.

## Step 0 — Preflight (every firing)
1. Read `CLAUDE.md` + user memory (`.claude/projects/-workspace-solaris-android/memory/MEMORY.md`). Honour: German replies, Conventional Commits, NEVER commit the keystore, cross-repo=ticket-only.
2. Ensure the toolchain env (per session — see memory `toolchain-not-preprovisioned`): `JAVA_HOME=~/.bubblewrap/jdk/jdk-17.0.11+9`, `ANDROID_HOME=~/.bubblewrap/android_sdk`, `PATH` incl. `~/.npm-global/bin`. `git config --global --add safe.directory /workspace/solaris-android`.
3. `queue.py summary` for status (cold start: `queue.py rebuild --release-pr <n>`); fold any `.claude/state/verify-result.json` in with `queue.py verify-set …`; `queue.py mirror` prunes the cache + re-projects labels.
4. Decide the ONE stage to dispatch this firing (below) and spawn it as a fresh Agent. Re-read the queue after it returns.

## Dispatch order (one per firing)
1. If `verify-result.json` exists → fold it in, delete it.
2. If `verify_state.status == "owed"` and a sealed batch exists → dispatch **Verify** (background).
3. If planned units remain and (batch.count < 8) → dispatch **Builder** (build-ahead onto batch).
4. If (batch.count ≥ 8 OR no planned units) and batch unsealed and `verify_state` clear → dispatch **Builder seal** (push → CI → merge → set `verify_state=owed`).
5. Else → dispatch **Planner** (groom backlog, fill queue, file cross-repo tickets, park refinements).
6. If nothing to do (queue dry, no batch, backlog groomed) → idle; `/loop` re-fires later.

Stages: `stages/planner.md`, `stages/builder.md`, `stages/verify.md`.
