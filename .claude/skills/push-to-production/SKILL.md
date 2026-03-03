---
name: push-to-production
description: Release a new version with debug APK on GitHub. Use when user says "push to production", "release", "deploy", or "new version". Runs tests, bumps version, builds debug APK, merges main to release, tags, creates GitHub Release with APK download, and transitions Linear issues.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash, Task, mcp__linear__list_teams, mcp__linear__list_issues, mcp__linear__get_issue, mcp__linear__update_issue, mcp__linear__list_issue_statuses
argument-hint: [version]
disable-model-invocation: true
---

Release `main` to `release` with automated testing, version bump, debug APK build, GitHub Release, and Linear issue transitions. This is the only path to production.

## Phase 1: Pre-flight Checks

### 1.1 Verify Linear MCP

**ALWAYS call `mcp__linear__list_teams` directly.** Do NOT try to determine MCP availability by inspecting the tool list, checking settings, or reasoning about it — you MUST actually invoke the tool and check the result. If the call fails or returns an error, **warn** but do not stop — Linear state transitions are cosmetic, the release can proceed without them.

Extract the team name from the response (there should only be one team in most cases).

### 1.2 Git State

```bash
git branch --show-current
git status --porcelain
```

**Requirements:**
- Must be on `main` branch
- Working tree must be clean (no uncommitted changes)
- Must be up to date with remote: `git fetch origin && git rev-list --count HEAD..origin/main` must be `0`

If any check fails, **STOP** and tell the user what to fix.

### 1.3 Check for Pending PLANS.md

Read `PLANS.md` from project root (if it exists). If it contains incomplete tasks (tasks not marked as done), **STOP**: "There are incomplete tasks in PLANS.md. Finish implementation first or clear the plan before releasing."

If PLANS.md doesn't exist or has no incomplete tasks, continue.

### 1.4 Build & Tests

Run the `verifier` agent (full mode) to confirm unit tests and build pass:

```
Use Task tool with subagent_type "verifier"
```

If verifier reports failures, **STOP**. Do not proceed with a broken build.

### 1.5 Release Branch Exists

```bash
git rev-parse --verify origin/release
```

If `release` branch doesn't exist, **STOP** and tell the user to create it:
```
git checkout -b release && git push -u origin release && git checkout main
```

### 1.6 Diff Assessment

Check what's changing between `release` and `main`:

```bash
git log origin/release..origin/main --oneline
git diff origin/release..origin/main --stat
```

If there are no commits to promote, **STOP**: "Nothing to promote. `main` and `release` are identical."

Show the user the commit list and file diff summary.

**First release (no prior tags):** If `git describe --tags` fails, this is the first tagged release. Use the full commit history and treat the current `build.gradle.kts` version as the starting version.

**IMPORTANT:** Wait for the user to acknowledge the diff summary before proceeding to Phase 2.

## Phase 2: Version & Changelog

### 2.1 Determine Version

Follows [Semantic Versioning 2.0.0](https://semver.org/):

1. Read `CHANGELOG.md` and extract the current version from the first `## [x.y.z]` header. If `CHANGELOG.md` doesn't exist, this is the first release — create it fresh.
2. If `<arguments>` contains a version (e.g., `2.0.0`):
   - Validate it's valid semver (X.Y.Z)
   - Validate it's strictly higher than current version
   - If invalid, **STOP**: "Invalid version. Must be higher than current [current]."
3. If no argument, **deduce the bump from the commits being promoted** (from Phase 1.6):
   - **MAJOR** (`x+1.0.0`): Incompatible/breaking changes — removed features, changed data formats, breaking Health Connect behavior changes
   - **MINOR** (`x.y+1.0`): Backward-compatible new functionality — new screens, new health data types, new features, significant UI additions
   - **PATCH** (`x.y.z+1`): Backward-compatible bug fixes — bug fixes, UI tweaks, refactoring, performance improvements, documentation, dependency updates
   - When commits span multiple categories, use the **highest** bump level (MAJOR > MINOR > PATCH)
   - Show the user which bump level was chosen and why, so they can override if they disagree

### 2.2 Update Version in build.gradle.kts

Edit `app/build.gradle.kts`:
- Set `versionName` to the new semver string (e.g., `"1.1.0"`)
- Increment `versionCode` by 1 from its current value (Play Store requires monotonically increasing integer)

### 2.3 Write Changelog Entry

Follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

See [references/changelog-guidelines.md](references/changelog-guidelines.md) for the full INCLUDE/EXCLUDE criteria and writing style rules.

**Process:**

1. Review the commit list from Phase 1.6
2. **Determine the net effect against production** — use `git diff origin/release..origin/main --stat` (not the commit list) as the source of truth for what actually changed. Commits that introduce and fix the same issue within the cycle produce zero changelog entries.
3. Filter out purely internal changes (they get zero entries)
4. Move any items from the `## [Unreleased]` section into the new version entry
5. Write a `## [version] - YYYY-MM-DD` entry, grouping changes under these section headers (omit empty sections):
   - `### Added` — new features, new screens
   - `### Changed` — changes to existing functionality, UI improvements
   - `### Deprecated` — features that will be removed in a future release
   - `### Removed` — removed features
   - `### Fixed` — bug fixes
   - `### Security` — security-related changes, vulnerability fixes
6. Group minor fixes into single items (e.g., "Minor bug fixes" or "Minor UI polish")
7. Keep each section concise — aim for 3-8 items total across all sections
8. Insert the new entry between `## [Unreleased]` and the previous version (keep Unreleased section empty)
9. Update the comparison links at the bottom of the file:
   - `[Unreleased]` link: compare new version tag to HEAD
   - New version link: compare previous version tag to new version tag
   - Format: `[Unreleased]: https://github.com/lucaswall/health-helper/compare/vNEW...HEAD`
   - Format: `[NEW]: https://github.com/lucaswall/health-helper/compare/vOLD...vNEW`
   - For the first release: `[NEW]: https://github.com/lucaswall/health-helper/releases/tag/vNEW`

## Phase 3: Build Release Artifact

### 3.1 Build Debug APK

Build the debug APK that will be attached to the GitHub Release:

```bash
./gradlew assembleDebug
```

### 3.2 Locate APK

Find the built APK:

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

If the APK doesn't exist, **STOP**: "Debug APK build failed. Check build output."

Record the path for Phase 5.

## Phase 4: Commit, Merge & Tag

### 4.1 Commit Version Bump to Main

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "release: v<version>"
git push origin main
```

### 4.2 Merge Main to Release

```bash
git checkout release
git pull origin release
git merge origin/main --no-edit
git push origin release
```

If merge conflicts occur, **STOP** and tell the user to resolve them manually.

### 4.3 Tag Release

Create an annotated git tag on the `release` branch and push it:

```bash
git tag -a "v<version>" -m "v<version>"
git push origin "v<version>"
git checkout main
```

## Phase 5: GitHub Release with APK

### 5.1 Create GitHub Release

Extract release notes from `CHANGELOG.md` — the content between the new `## [version]` header and the next `## [` header (excluding both headers).

Write the release notes to a temporary file:

```
Use the Write tool to create release-notes.md with the extracted changelog content
```

Create the release with the debug APK attached:

```bash
gh release create "v<version>" app/build/outputs/apk/debug/app-debug.apk --title "v<version>" --notes-file release-notes.md --verify-tag
```

Clean up:

```bash
rm -f release-notes.md
```

**Flags reference:**
- `--verify-tag` — Abort if the tag doesn't exist on the remote (safety check)
- `app/build/outputs/apk/debug/app-debug.apk` — Attach the debug APK as a release asset
- Do NOT use `--draft` or `--prerelease` — all releases from this skill are production releases

**Error handling:** If `gh release create` fails, **do NOT stop the release**. Log a warning in the Phase 6 report:
```
**Warning:** GitHub Release creation failed: [error message]. Create manually with:
gh release create "v<version>" app/build/outputs/apk/debug/app-debug.apk --title "v<version>" --notes-file release-notes.md --verify-tag
```

The git tag and merge already succeeded — the GitHub Release is cosmetic and can be created manually later.

## Phase 6: Post-Release

### 6.1 Move Issues to Released

Transition all Linear issues in "Done" or "Merge" to "Released" now that the code is live.

1. Look up the Released state UUID using `mcp__linear__list_issue_statuses` with team "Health Helper". Find the status with `name: "Released"` (or similar — check what states exist in the team).

2. Query issues stuck in "Merge" state (PR was merged but Linear automation didn't fire):
   ```
   mcp__linear__list_issues with team: "Health Helper", state: "Merge"
   ```

3. Query all issues in "Done" state:
   ```
   mcp__linear__list_issues with team: "Health Helper", state: "Done"
   ```

4. For each issue found (from both queries), transition to Released using the **state UUID** (both Done and Released are `type: completed` — passing by name could silently no-op):
   ```
   mcp__linear__update_issue with id: <issue-id>, state: "<released-state-uuid>"
   ```
   **Batch efficiently:** Call up to 10 `update_issue` calls in parallel. If there are more than 30 issues, update the first 30 and note the remainder in the report for manual transition.

5. Collect the list of moved issues (identifier + title) for the report.

If no issues are in Done or Merge, that's fine — skip silently.

If the Linear MCP is unavailable (tools fail), **do not STOP** — log a warning in the report and continue. The release itself succeeded; issue state is cosmetic.

### 6.2 Report

```
## Release Complete

**Version:** X.Y.Z
**Promoted:** main -> release
**Commits:** N commits
**APK:** Attached to GitHub Release (debug build)
**GitHub Release:** [Created | Failed (see warning above)]

### Issues Released
[List of HEA-xxx: title moved from Done -> Released, or "None"]

### Install on Device
1. Open the GitHub Release URL on your phone's browser
2. Download `app-debug.apk`
3. Enable "Install from unknown sources" if prompted (one-time)
4. Install and run

### Next Steps
- Verify the release at: https://github.com/lucaswall/health-helper/releases/tag/v<version>
- Install and test on device
```

## Error Handling

| Situation | Action |
|-----------|--------|
| Not on `main` | STOP — switch to main first |
| Dirty working tree | STOP — commit or stash |
| Behind remote | STOP — pull latest |
| Build/tests fail | STOP — fix before releasing |
| Incomplete tasks in PLANS.md | STOP — finish implementation first |
| No commits to promote | STOP — nothing to do |
| Release branch missing | STOP — tell user to create it |
| Invalid/lower version argument | STOP — must be valid semver higher than current |
| Debug APK build fails | STOP — fix build before proceeding |
| Merge conflicts | STOP — user resolves manually |
| GitHub Release creation fails | Warn in report — release succeeded, create manually later |

## Rules

- **Never skip tests** — Always run verifier before releasing
- **Never force-push** — Use normal merge only
- **No co-author attribution** — Commit messages must NOT include `Co-Authored-By` tags
- **Debug APK for distribution** — Until signing is configured, all releases ship debug APKs
- **Semantic Versioning 2.0.0** — Version bumps follow semver rules
- **versionCode always increments** — By exactly 1 each release (Play Store requirement if ever used)
- **Linear is cosmetic** — Issue state transitions are nice-to-have. Never block a release because Linear MCP is down.
- **Stop on any failure** — Better to abort than ship a broken release
- **Changelog describes user-visible changes** — Not internal refactors or tooling
- **GitHub Release is non-blocking** — If it fails, the git release (tag + merge) already succeeded
