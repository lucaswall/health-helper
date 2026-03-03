---
name: investigate
description: Read-only investigation that reports findings WITHOUT creating plans or modifying code. Use when user says "investigate", "check why", "look into", "diagnose", or wants to understand a problem before deciding on action. Accesses deployment logs and codebase.
argument-hint: <what to investigate>
allowed-tools: Read, Glob, Grep, Task, Bash
disable-model-invocation: true
---

Investigate issues and report findings. Does NOT create plans or modify code.

## Purpose

- Investigate reported issues (API errors, wrong data, unexpected behavior)
- Debug build or runtime issues using build output and logs
- Analyze codebase to understand behavior
- Examine configuration and environment issues
- **Report findings only** - user decides next steps

## Arguments

$ARGUMENTS should describe what to investigate:
- What happened vs what was expected
- Error messages or unexpected values
- Which environment (if applicable) — if not specified, ask
- Build variant or flavor if it's a build issue
- Any context that helps narrow the scope

## Context Gathering

**IMPORTANT: Do NOT hardcode MCP names or folder paths.** Always read CLAUDE.md to discover:

1. **Available MCP servers** - Look for "MCP SERVERS", "MCPs", or similar sections to find:
   - **Sentry MCP** — Always available for crash/error investigation (use ToolSearch to load tools)
   - Deployment MCPs for logs and service status (Firebase, Play Console, etc.)
   - Any other configured MCPs

2. **Project structure** - Look for "STRUCTURE" or "FOLDER STRUCTURE" sections to understand:
   - Where source code and documents are stored
   - Naming conventions and organization

3. **Domain concepts** - Look for sections describing:
   - Data schemas and formats
   - Business rules and validation
   - API endpoints and their behavior

4. **Environments** - Look for "ENVIRONMENTS" section to discover:
   - Environment names (production, staging, etc.)
   - Associated branches and build variants
   - Deployment service configurations

## Investigation Workflow

### Step 1: Classify the Investigation Type

Based on $ARGUMENTS, determine what you're investigating:

| Category | Indicators | Primary Tools |
|----------|-----------|---------------|
| **Build** | Compilation errors, Gradle failures, dependency issues | Codebase, Build output |
| **Runtime** | Crashes, ANRs, unexpected behavior | Sentry, Codebase, Logcat output |
| **Data** | Wrong values, missing records, unexpected state | Codebase, Database queries |
| **Performance** | Slow rendering, memory issues, jank | Sentry, Codebase, Profiler output |
| **Auth/Permissions** | Login failures, permission errors, Health Connect access | Codebase, Manifest |
| **General** | Unknown cause, need exploration | Sentry, All available tools |

### Step 2: Gather Evidence

**For Sentry (crashes, errors, performance):**

The Sentry MCP is available for querying production crash data and error reports. Use ToolSearch to load Sentry tools before calling them. Read CLAUDE.md SENTRY section for the organization slug, project slug, and region URL — pass these to Sentry MCP tools directly.

**When to use Sentry:**
- User reports a crash or error happening in production
- Investigating runtime exceptions, ANRs, or unhandled errors
- Need crash frequency, affected user count, or error trends
- User provides a Sentry issue URL or issue ID
- Need stack traces from production (more reliable than local logcat)

**Sentry investigation workflow:**
1. **Get org/project from CLAUDE.md** — Read SENTRY section for organization, project, and region URL
2. **Search for issues** — Use `mcp__sentry__search_issues` with natural language (e.g., "unresolved crashes from last week", "NullPointerException errors")
3. **Get issue details** — Use `mcp__sentry__get_issue_details` with the issue ID or URL for full stack traces and metadata
4. **Analyze root cause** — Use `mcp__sentry__analyze_issue_with_seer` for AI-powered root cause analysis
5. **Search events** — Use `mcp__sentry__search_events` for counts, aggregations, or individual error events
6. **Filter issue events** — Use `mcp__sentry__search_issue_events` to filter events within an issue by time, environment, release, or user
7. **Check distributions** — Use `mcp__sentry__get_issue_tag_values` to see how an issue is distributed across devices, OS versions, releases, etc.

**Tips:**
- Start with `search_issues` for broad exploration, `get_issue_details` when you have a specific issue
- Use `search_events` for "how many errors today?" style questions
- Cross-reference Sentry stack traces with local codebase to identify the exact code path
- Check tag distributions (device, OS, release) to understand if the issue is device-specific

**For Codebase Analysis:**
- Use Grep/Glob for specific searches
- Use Task tool with `subagent_type=Explore` for broader exploration
- Read relevant source files, configs, and tests
- Check `build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`

**For Build Issues:**
1. Run `./gradlew assembleDebug 2>&1` to reproduce
2. Check Gradle configuration files
3. Check dependency versions in `gradle/libs.versions.toml`
4. Look for version conflicts or missing dependencies

**For Runtime Issues (crashes, ANRs, unexpected behavior):**
1. Pull device logs using the ADB commands in the section below
2. Trace the execution flow through the codebase
3. Check ViewModel/StateFlow logic
4. Look for coroutine scope issues

**For Data Issues:**
- Examine data models and Room entities
- Check repository implementations
- Trace data flow from Health Connect/network to UI
- Look for transformation errors in mappers

### ADB Device Debugging

When investigating runtime issues, use the following commands **in this order**. Do NOT trial-and-error with random grep filters. The app package is `com.healthhelper.app`.

**Run all three of these in parallel first** to get a comprehensive snapshot:

#### 1. Crash logs (MOST IMPORTANT — start here)
```bash
adb shell "dumpsys dropbox --print" 2>/dev/null | grep -B5 -A 60 "Process: com.healthhelper.app"
```
- Persists across app restarts — captures crashes even if the app was killed and restarted
- Shows full stack traces, timestamps, UID, PID, process runtime, foreground state
- Categories: `data_app_crash` (Java/Kotlin), `data_app_native_crash` (NDK), `data_app_anr` (ANR)
- Parse the `Caused by:` chain to find the root exception

#### 2. App metadata (permissions, version, UID)
```bash
adb shell "dumpsys package com.healthhelper.app" 2>/dev/null | grep -E "versionCode|versionName|targetSdk|userId|granted|requested permissions|install permissions|INTERNET|CAMERA|health\." | head -30
```
- Verify installed version matches what you expect
- Check all permissions are granted (especially INTERNET, CAMERA, Health Connect)
- Note the UID — compare with crash log UIDs to confirm crashes are from current install

#### 3. Recent logcat (app-specific)
```bash
adb logcat -d -t 500 --pid=$(adb shell pidof -s com.healthhelper.app 2>/dev/null) 2>/dev/null | grep -v "ResourcesManager\|VRI\[Main\|OplusPredictive\|surfaceControl\|Camera2Presence\|OplusCamera\|setClientInfo" | tail -80
```
- Only useful if the app is **currently running** — logcat is ephemeral
- The `--pid` filter requires the app to be alive; falls back gracefully if not
- Exclusion filter removes noisy system messages common on OnePlus/Oppo devices
- If the app is not running, skip this and rely on dropbox

#### When logcat PID filter fails (app not running)
```bash
adb logcat -d -b crash -t 50 2>/dev/null
```
- The crash buffer persists longer than the main buffer
- Shows recent crash stack traces across all apps — grep for `healthhelper`

#### Permission deep-dive (when permissions are suspected)
```bash
adb shell "dumpsys package com.healthhelper.app" 2>/dev/null | grep -A 30 "runtime permissions"
```

#### Health Connect specific
```bash
adb shell "dumpsys package com.healthhelper.app" 2>/dev/null | grep -E "health\.|HEALTH"
```

**Key principles:**
- **Always start with `dumpsys dropbox`** — it's the most reliable source for crashes. Logcat may have rotated away.
- **Compare UIDs** between crash logs and current install — if they differ, the app was reinstalled and old crashes may not be relevant to the current build.
- **Parse the full `Caused by:` chain** — the top-level exception (e.g., `SecurityException`) is often misleading; the root cause is at the bottom (e.g., `ErrnoException: EPERM`).
- **Check `Process-Runtime`** in dropbox output — short runtimes (< 5s) indicate crash-on-startup or crash-restart loops.
- **Never use `adb logcat -uall`** — can cause memory issues on large repos.
- **Run parallel** — all three primary commands above are independent and should be called simultaneously.

### Step 3: Form Conclusions

After gathering evidence, determine:

1. **Root Cause Identified** - You found what's causing the issue
2. **Root Cause Suspected** - Strong hypothesis but not 100% certain
3. **Multiple Possibilities** - Several potential causes, need more info
4. **Nothing Wrong Found** - Investigation shows system working correctly
5. **Cannot Determine** - Insufficient information to conclude

## Investigation Report Format

Write findings to the conversation (NOT to a file):

```
## Investigation Report

**Subject:** [What was investigated]
**Sentry Issues:** [Sentry issue IDs/URLs examined, if any — e.g., HEALTH-HELPER-5]
**Environment:** [production | staging | codebase-only]
**Conclusion:** [Root Cause Identified | Suspected | Multiple Possibilities | Nothing Wrong | Cannot Determine]

### Context
- **MCPs used:** [list MCPs accessed]
- **Environment queried:** [production | staging | N/A]
- **Files examined:** [list key files checked]
- **Logs reviewed:** [build output, dumpsys dropbox, logcat, dumpsys package, etc. if applicable]

### Evidence
[What you found - be specific with data points, log excerpts, file contents]

### Findings

[Explain what you discovered. If root cause found, explain it clearly.
If nothing wrong, explain what was checked and why it appears correct.
If uncertain, list possibilities ranked by likelihood.]

### Recommendations (Optional)
[Only if you have specific suggestions - do NOT write a fix plan]
```

## Build Debugging Guidelines

When investigating build issues:

1. **Check Gradle output** - Run `./gradlew assembleDebug 2>&1` and examine errors
2. **Check dependency catalog** - Review `gradle/libs.versions.toml` for version issues
3. **Check build scripts** - Review `build.gradle.kts` files
4. **Look for patterns** - Repeated errors, AGP/Kotlin compatibility issues
5. **Check configuration** - Build variants, product flavors, signing configs

## Error Handling

| Situation | Action |
|-----------|--------|
| $ARGUMENTS is vague | Ask for more specific details |
| CLAUDE.md doesn't exist | Continue with codebase-only investigation |
| MCP not available | Skip that MCP, note in report what couldn't be checked |
| Sentry org/project not found | Note in report; continue with codebase and ADB investigation |
| Sentry has no matching issues | Note "no Sentry issues found" in report; may mean issue is not in production yet |
| File/resource not found | Document in report (may be relevant) |
| Cannot reproduce issue | Document steps taken, request more context |
| Logs unavailable | Note in report, suggest alternative approaches |
| No device connected | Skip ADB commands, note in report. Investigate codebase only. |
| App not running on device | Use `dumpsys dropbox` and crash buffer instead of logcat PID filter |
| Dropbox empty for app | App hasn't crashed recently. Check logcat and codebase analysis instead |

## Rules

- **Report only** - Do NOT modify source code or files
- **No plans** - Do NOT write PLANS.md or fix plans
- **Discover MCPs** - Read CLAUDE.md to find available tools
- **Explicit environment** - ALWAYS pass the `environment` parameter to deployment MCP tools when supported; never rely on CLI defaults
- **Be thorough** - Check multiple sources before concluding
- **Be specific** - Include exact values, line numbers, timestamps
- **Be honest** - If uncertain, say so; if nothing wrong, say so

## What NOT to Do

1. **Don't create PLANS.md** - This skill only reports
2. **Don't modify code** - Investigation is read-only
3. **Don't assume MCPs** - Discover from CLAUDE.md
4. **Don't conclude prematurely** - Gather sufficient evidence first
5. **Don't force findings** - "Nothing wrong" is a valid conclusion

## Termination

When you finish investigating, output the investigation report.

**If bugs or issues were found that need fixing**, end with:

```
---
Investigation complete. Issues found that may need fixing.

Would you like me to create a fix plan? Say 'yes' or run `/plan-fix` with the context above.
(Fix plans will create Linear issues with your project's issue prefix in Todo state)
```

**If nothing wrong was found or no fix needed**, end with:

```
---
Investigation complete.

To take action based on these findings:
- For bug fixes: Use `plan-fix` with this context (creates Linear issues in Todo)
- For feature changes: Use `plan-inline` with specific request (creates Linear issues in Todo)
- For further investigation: Provide more details and run investigate again
```

Do not offer to implement fixes directly. Report findings and offer skill chaining if appropriate.
