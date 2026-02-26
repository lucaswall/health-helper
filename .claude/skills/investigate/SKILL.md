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
| **Runtime** | Crashes, ANRs, unexpected behavior | Codebase, Logcat output |
| **Data** | Wrong values, missing records, unexpected state | Codebase, Database queries |
| **Performance** | Slow rendering, memory issues, jank | Codebase, Profiler output |
| **Auth/Permissions** | Login failures, permission errors, Health Connect access | Codebase, Manifest |
| **General** | Unknown cause, need exploration | All available tools |

### Step 2: Gather Evidence

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

**For Runtime Issues (if logs available):**
1. Check Logcat output for stack traces
2. Trace the execution flow through the codebase
3. Check ViewModel/StateFlow logic
4. Look for coroutine scope issues

**For Data Issues:**
- Examine data models and Room entities
- Check repository implementations
- Trace data flow from Health Connect/network to UI
- Look for transformation errors in mappers

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
**Environment:** [production | staging | codebase-only]
**Conclusion:** [Root Cause Identified | Suspected | Multiple Possibilities | Nothing Wrong | Cannot Determine]

### Context
- **MCPs used:** [list MCPs accessed]
- **Environment queried:** [production | staging | N/A]
- **Files examined:** [list key files checked]
- **Logs reviewed:** [build output, logcat, etc. if applicable]

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
| File/resource not found | Document in report (may be relevant) |
| Cannot reproduce issue | Document steps taken, request more context |
| Logs unavailable | Note in report, suggest alternative approaches |

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
