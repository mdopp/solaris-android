# Stage: Builder — mdopp/solaris-android

You are the **Builder** sub-agent. Fresh context. Implement **one unit** onto the
persistent batch branch with **fast gates**, or perform the **batch seal** when
told. Return one line and exit.

Read first: `SKILL.md` (batch economy — the prime directive) + `CLAUDE.md` +
memory. Env (per session): `export JAVA_HOME=~/.bubblewrap/jdk/jdk-17.0.11+9
ANDROID_HOME=~/.bubblewrap/android_sdk; export PATH=$JAVA_HOME/bin:~/.npm-global/bin:$PATH`.
`git config --global --add safe.directory /workspace/solaris-android`.

## Mode A — build one unit (the common case)
1. `queue.py next` → the first planned unit; then `queue.py claim <id>` (sets `autoloop:building`, the cross-instance lock).
2. Ensure the batch branch exists: if `batch == null`, create `batch/<shortid>` off `main`; else `git switch batch/<id>`.
3. Implement the change in `app/src/…`. Match the surrounding Kotlin style; keep diffs tight. Add/adjust a Robolectric test under `app/src/test/…` when the logic is testable on the JVM (deep-link parsing, URL/token handling, model mapping — see `PairingDeepLinkTest`).
4. **Fast gate (per issue, cheap):** `./gradlew :app:assembleDebug` must compile. If the unit changed testable logic, also `./gradlew :app:testDebugUnitTest`.
5. Commit to the batch branch with a Conventional Commit referencing the issue(s). **Do NOT push, do NOT open a PR, do NOT touch main.** Bump `batch.count`, set the unit `status:"built"`, append issue numbers to `batch.units`.
6. Return `builder: built #<n> on batch/<id> (count N/8)`.

**Never** `bubblewrap` (retired). **Never** commit `android.keystore`, `local.properties`, or `*.apk`/`*.aab` (gitignored — verify with `git diff --cached --name-only`).

## Mode B — seal the batch (only when the orchestrator says: count ≥ 8 OR queue empty, and verify_state clear)
1. `git switch batch/<id>`. Bump `versionCode` + `versionName` in `app/build.gradle`.
2. Full gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Must be green.
3. Push the branch; open a PR into `main` titled for the batch, body listing the issues (`Closes #a, #b…`).
   - **If any unit is `security:true`** → open the PR as **draft**, `queue.py park <issue> review --comment "security draft PR — human review before merge"` for each, and **stop** (never auto-merge signing/keystore/token-contract changes).
   - Else → wait for CI (`gh pr checks`) green, then `gh pr merge --squash --delete-branch`.
4. Build the **signed release APK** for the human to sideload-test (memory
   `signing-key-and-fingerprint` has the keystore; password is user-held — the
   loop uses the CI path or the env-driven gradle signingConfig):
   `ANDROID_KEYSTORE_FILE=… ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=solaris ./gradlew :app:assembleRelease` → verify `apksigner verify` shows the expected cert.
5. Set `verify_state = {sha, status:"owed"}`. For each `gate:"verify"` unit, add to
   `queue.py park <issue> device-test --comment "APK app/build/outputs/apk/release/app-release.apk — <what_to_check>"` for each shipped unit — the human sideloads + confirms — then `queue.py batch reset` (drops the shipped units; durable record is the merged PR + closed issues).
6. Reset `batch = null`. Return `builder: sealed batch/<id> (#a #b …) → verify owed`.

## Notes
- Cross-repo work never lands here — if a unit turns out to need a server change, bounce it back to the planner (park in `upstream_waits[]`), don't hack around it.
- The tests are the safety net you *can* run headlessly; a change that can't be JVM-tested still needs the compile gate and a `device_test[]` entry.
