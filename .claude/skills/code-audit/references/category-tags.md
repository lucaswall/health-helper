# Category Tags Reference

## Audit Tags (validated during code audit)

| Tag | Description | OWASP / Mobile |
|-----|-------------|----------------|
| `[security]` | Exposed secrets, missing auth, insecure communication | A01-A03, A07, M1-M4 |
| `[permission]` | Missing runtime permissions, wrong permission groups, Health Connect permissions | M1 |
| `[injection]` | SQL injection (Room), intent injection, deep link exploitation | A03, M8 |
| `[storage]` | Insecure data storage, unencrypted SharedPreferences, cleartext health data | M2, M9 |
| `[memory-leak]` | Unbounded growth, retained Activity/Context refs, static View references | - |
| `[bug]` | Logic errors, data corruption, off-by-one | - |
| `[resource-leak]` | Cursors, InputStreams, database connections not closed | - |
| `[coroutine]` | Unstructured concurrency, wrong dispatcher, missing exception handling | - |
| `[lifecycle]` | Lifecycle-unaware collection, leaking across lifecycle states | - |
| `[edge-case]` | Unhandled scenarios, boundary conditions | - |
| `[convention]` | CLAUDE.md violations | - |
| `[type]` | Unsafe casts, platform types, unvalidated external data | - |
| `[dependency]` | Vulnerable or outdated libraries | A06 |
| `[rate-limit]` | API quota exhaustion risks | - |
| `[logging]` | Missing logs, wrong levels, log overflow, sensitive data in logs | - |
| `[dead-code]` | Unused functions, unreachable code | - |
| `[duplicate]` | Repeated logic | - |
| `[test]` | Useless/duplicate tests, no assertions | - |
| `[practice]` | Anti-patterns | - |

**OWASP Mobile Top 10 (2024) Reference:**
- M1: Improper Platform Usage (permissions, intents, Content Providers)
- M2: Insecure Data Storage (SharedPreferences, databases, logs)
- M3: Insecure Communication (cleartext, missing cert pinning)
- M4: Insecure Authentication (weak session, missing biometric)
- M8: Code Tampering (missing ProGuard/R8, no integrity checks)
- M9: Reverse Engineering (exposed API keys, debug builds)

**OWASP Top 10 (2021) Reference:**
- A01: Broken Access Control (auth bypass, IDOR, privilege escalation)
- A02: Cryptographic Failures (secrets exposure, weak crypto)
- A03: Injection (SQL, command)
- A06: Vulnerable Components (outdated dependencies)
- A07: Authentication Failures (weak sessions, missing auth)

## Non-Audit Tags (preserved without validation)

| Tag | Description |
|-----|-------------|
| `[feature]` | New functionality to add |
| `[improvement]` | Enhancement to existing functionality |
| `[enhancement]` | Similar to improvement |
| `[refactor]` | Code restructuring without behavior change |
| `[docs]` | Documentation updates |
| `[chore]` | Maintenance tasks |

Non-audit issues are preserved in Linear Backlog without validation.

## Linear Label Mapping

When creating Linear issues, map category tags to Linear labels:

| Category Tags | Linear Label |
|---------------|--------------|
| `[security]`, `[dependency]`, `[permission]`, `[injection]`, `[storage]` | Security |
| `[bug]`, `[coroutine]`, `[lifecycle]`, `[edge-case]`, `[type]`, `[logging]` | Bug |
| `[memory-leak]`, `[resource-leak]`, `[rate-limit]` | Performance |
| `[convention]` | Convention |
| `[dead-code]`, `[duplicate]`, `[test]`, `[practice]`, `[docs]`, `[chore]` | Technical Debt |
| `[feature]` | Feature |
| `[improvement]`, `[enhancement]`, `[refactor]` | Improvement |

## Linear Priority Mapping

Map priority levels to Linear priority values:

| Priority Tag | Linear Priority |
|--------------|-----------------|
| `[critical]` | 1 (Urgent) |
| `[high]` | 2 (High) |
| `[medium]` | 3 (Medium) |
| `[low]` | 4 (Low) |
