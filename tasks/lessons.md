# Lessons

Entries: the pattern → what went wrong → the rule to follow.

## Verify the build environment before writing code
- **What went wrong:** Was about to scaffold a new Gradle/Java module before checking the toolchain. The machine had NO JDK (only a VASSAL-bundled JRE) — nothing could have compiled or been verified.
- **Rule:** On any build/compile task, confirm the required toolchain exists *first* (`java -version`, `JAVA_HOME`, install dirs). Surface a missing toolchain as a blocker immediately rather than producing unverifiable code. Bias toward environment/config causes on platform-specific work (per global CLAUDE.md).

## Don't use PowerShell syntax in the Bash tool
- **What went wrong:** Ran `git commit -m @'...'@` (a PowerShell here-string) inside the **Bash** tool. Bash treated `@` as a literal char, leaving a stray `@` on the commit subject and body. Fixed with `git commit --amend`.
- **Rule:** Bash tool = bash quoting (single/double quotes, `$'...'`). PowerShell tool = PowerShell syntax (`@'...'@` here-strings, `$env:`). Never mix. For multiline commit messages in bash, use multiple `-m` flags.

## winget installs aren't visible to running shells
- **What went wrong:** After `winget install` of JDK 21, the machine `JAVA_HOME`/PATH updated (registry) but the agent's spawned shells still didn't see `java`.
- **Rule:** After installing a tool mid-session, don't assume PATH updated. Read the machine env var from the registry (`[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')`), locate the install dir, and set `$env:JAVA_HOME` / prepend PATH explicitly per command until a fresh shell is available.

## Gradle project paths here are flat
- **What went wrong (potential):** AGENTS.md shows `./gradlew :game-app:game-core:test`, but `settings.gradle.kts` registers projects flat via `include(":game-core")` + `projectDir` override.
- **Rule:** Trust `settings.gradle.kts` for project paths: use `:game-core`, `:smoke-testing`, `:game-headless`, etc. — not `:game-app:...`.
