# Post-Implementation Cleanup Procedures

After documenting results (skip in single-agent fallback mode), the lead MUST clean up everything:

## 1. Remove Worktrees

```bash
# Remove each worktree (--force handles any uncommitted leftovers)
git worktree remove _workers/worker-1 --force
git worktree remove _workers/worker-2 --force
# ... repeat for each worker
```

## 2. Remove Worker Directory

```bash
# Safety net — remove the entire _workers/ directory
rm -rf _workers/

# Prune stale worktree metadata from .git/worktrees/
git worktree prune
```

## 3. Delete Worker Branches

```bash
# Worker branches are already merged — safe delete
git branch -d <FEATURE_BRANCH>-worker-1
git branch -d <FEATURE_BRANCH>-worker-2
# ... repeat for each worker
```

## 4. Sync Dependencies

```bash
# Ensure main project builds correctly with merged build.gradle.kts/libs.versions.toml
./gradlew assembleDebug
```

This catches any dependency changes from merged code (new imports, updated version catalog entries). Fast no-op if nothing changed.

## 5. Verify Clean State

```bash
git worktree list
```

Should show only the main worktree. If stale entries remain, run `git worktree prune` again.

**Note:** `TeamDelete` was already called in the Post-Worker Phase (step 2). If it wasn't called there for any reason, call it now.
