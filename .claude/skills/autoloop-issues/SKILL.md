---
name: autoloop-issues
description: Orchestrates an autonomous issue-resolution pipeline for mdopp/solaris-android — Planner → Builder → Verify — coordinated through a shared work queue, spawning each stage as a fresh sub-agent so the loop session stays clean. Adapted from the solarisbay autoloop for a native Android/Gradle app: "verify" is Gradle build + Robolectric tests + a signed release APK (there is no server/box to deploy to — on-device behaviour is human-gated by sideloading the APK). Cross-repo needs become tickets in solarisbay/servicebay, never built here. Signing-key / device-token-contract changes open as a DRAFT PR for human review. Resumable via .claude/state/work-queue.json. Use when the user asks to "burn down the android backlog", "work the solaris-android issues autonomously", or invokes /loop with this skill.
---

# Autoloop orchestrator — mdopp/solaris-android

You are the **coordinator** of an autonomous issue-resolution pipeline for the
**native Android app**. You do **not** write code or groom issues yourself — you
run a tight dispatch loop that **spawns a fresh sub-agent per stage** and routes
work through one shared file, `.claude/state/work-queue.json`.

```
   you (orchestrator) → preflight → read queue → dispatch ONE stage → re-read → cadence
 PLANNER ──fills──▶ work-queue.json ──▶ BUILDER ──batch seal → CI → merge──▶ VERIFY ──▶ release(APK)
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

## The shared work queue (the only handoff)
`.claude/state/work-queue.json` is the single source of truth between stages.
Create from `work-queue-template.json` (same dir) if absent. Key fields:
- `queue[]` — **units** the builder consumes: `{id, kind:"cluster"|"issue", issues[], theme, region, scope, acceptance, gate:"normal"|"verify", security:false, status:"planned"|"in_progress"|"built"|"blocked", pr, notes}`. A cluster is the work-unit; members never appear standalone.
- `batch` — the persistent integration branch `{branch, units[], count, sealed}`. Survives firings; reset to `null` after its release/merge completes.
- `needs_refinement[]` — **the human's worklist**: `{issue, question, comment_url, since}`. The planner parks anything it can't make actionable with the *specific* question.
- `awaiting_user[]` — external human comment unanswered; never the pipeline's to reply to.
- `review[]` — **the human's pre-merge review list**: `{issue, pr, flag, since}` for `security:true` changes opened as **draft** PRs (never auto-merged).
- `device_test[]` — **the human's on-device worklist**: `{issue, pr, apk, what_to_check, since}` — a merged unit whose behaviour only the phone can confirm. This replaces solarisbay's box `/verify`.
- `verify_state` — `{sha, status:"owed"|"verifying"|"green"|"red", detail, since}`. "verify" here = build + tests + signed APK. `owed`→`verifying`→`green`|`red`.
- `blocked[]` / `upstream_waits[]` — parked work; `upstream_waits[]` = `{issue, cross_repo_issue, reason, since}` for a local issue blocked on an unmerged **solarisbay/servicebay** ticket the planner filed (cross-repo = ticket only — see project memory). Re-checked each run.
- `completed[]`, `release_warnings[]`, `notes[]`.

**Label mirror (one-way).** The file is source of truth; mirror to GitHub labels
so a human sees the same worklist: `blocked[]`→`autoloop:blocked`,
`needs_refinement[]`→`autoloop:needs-refinement`, `device_test[]`→`autoloop:device-test`.
Derived from the file every run, never the reverse.

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
3. Read the queue; fold any `.claude/state/verify-result.json` into `verify_state`; reconcile labels.
4. Decide the ONE stage to dispatch this firing (below) and spawn it as a fresh Agent. Re-read the queue after it returns.

## Dispatch order (one per firing)
1. If `verify-result.json` exists → fold it in, delete it.
2. If `verify_state.status == "owed"` and a sealed batch exists → dispatch **Verify** (background).
3. If planned units remain and (batch.count < 8) → dispatch **Builder** (build-ahead onto batch).
4. If (batch.count ≥ 8 OR no planned units) and batch unsealed and `verify_state` clear → dispatch **Builder seal** (push → CI → merge → set `verify_state=owed`).
5. Else → dispatch **Planner** (groom backlog, fill queue, file cross-repo tickets, park refinements).
6. If nothing to do (queue dry, no batch, backlog groomed) → idle; `/loop` re-fires later.

Stages: `stages/planner.md`, `stages/builder.md`, `stages/verify.md`.
