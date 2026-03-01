# HealthHelper - Ideas

## Contents

| Feature | Summary |
|---------|---------|
| [Measurement Reminders](#measurement-reminders) | Scheduled alarms to remind users to take glucose and blood pressure readings |


---

## Measurement Reminders

### Problem

Glucose and blood pressure readings are most useful when taken consistently at specific times (e.g., fasting glucose every morning, BP twice daily). Users forget to take measurements, especially when the routine is new. Without reminders, logs have gaps that reduce their clinical value.

### Prerequisites

- Blood Glucose Scanner or Blood Pressure Scanner (at least one must exist for reminders to be useful)

### Goal

Let users set recurring alarms for glucose and blood pressure measurements, with snooze and dismissal, so they build a consistent measurement habit.

### Design

#### Reminder Configuration

- Accessed from Settings screen under a "Reminders" section.
- Each reminder has:
  - **Type:** Glucose or Blood Pressure.
  - **Time:** Hour and minute picker.
  - **Days:** Multi-select for days of the week (default: every day).
  - **Label** (optional): Short text, e.g., "Fasting glucose" or "Evening BP".
  - **Enabled toggle:** Turn individual reminders on/off without deleting.
- Users can create multiple reminders (e.g., morning glucose + evening BP + pre-lunch glucose).
- Add/edit/delete reminders from a list view.

#### Notification Behavior

- At the scheduled time, show a notification with:
  - Title: The label (or "Glucose Reading" / "Blood Pressure Reading" as default).
  - Body: "Time to take your measurement."
  - Actions: **"Log Now"** (opens the corresponding scanner screen) and **"Snooze"**.
- **Snooze:** Reschedules the notification for 10 minutes later. Snooze is available up to 3 times per reminder instance (30 minutes total). After the third snooze, the notification stays but the snooze action is removed.
- Tapping the notification body opens the corresponding scanner screen (glucose or BP).
- Notification uses a dedicated channel ("Measurement Reminders") so users can customize sound/vibration in system settings.

#### Alarm Behavior

- Alarms must fire even if the app is not running (use `AlarmManager` with exact alarms).
- Alarms survive device reboot (re-register via `BOOT_COMPLETED` receiver).
- If the device is in Do Not Disturb, the notification still posts but respects DND rules (no sound/vibration unless the channel is set to override).

### Architecture

- **Storage:** Room database table `reminders` with columns: id, type (glucose/bp), hour, minute, days (bitmask or JSON array of day constants), label, enabled.
- **Scheduling:** `AlarmManager.setExactAndAllowWhileIdle()` for reliable firing. Each reminder gets a unique `PendingIntent` keyed by reminder ID.
- **Boot receiver:** `BroadcastReceiver` registered for `BOOT_COMPLETED` that re-schedules all enabled reminders.
- **Notification:** `NotificationCompat` with action intents for "Log Now" (deep link to scanner) and "Snooze" (reschedule alarm +10 min).
- **Snooze tracking:** Transient counter stored in the notification's extras or a short-lived in-memory/SharedPreferences entry keyed by reminder ID + date. Resets daily.
- **Permissions:** `SCHEDULE_EXACT_ALARM` (API 31+), `POST_NOTIFICATIONS` (API 33+), `RECEIVE_BOOT_COMPLETED`.

### Edge Cases

- User changes system time or timezone - `AlarmManager` handles this natively; alarms fire at the wall-clock time.
- User creates a reminder for a time that already passed today - schedule for the next matching day.
- Multiple reminders at the same time - each fires independently as separate notifications.
- App updated while reminders are active - alarms persist across app updates (they're system-level).
- User disables exact alarm permission (API 31+ settings) - fall back to `setAndAllowWhileIdle()` (inexact but close), show a note in Settings that reminders may be delayed.
- Device manufacturer aggressive battery optimization kills alarms - document known issues per manufacturer (Xiaomi, Samsung, etc.) in a help section if needed.

### Implementation Order

1. Room database table and `ReminderRepository` (CRUD operations)
2. Reminder list UI in Settings (add, edit, delete, enable/disable toggle)
3. `AlarmManager` scheduling logic (set/cancel exact alarms)
4. `BroadcastReceiver` for alarm firing + notification display
5. Notification actions: "Log Now" deep link and "Snooze" intent
6. Snooze logic (reschedule +10 min, track count, limit to 3)
7. `BOOT_COMPLETED` receiver for alarm re-registration
8. Permission handling (`SCHEDULE_EXACT_ALARM`, `POST_NOTIFICATIONS`)

---

## Conventions

Rules for agents creating, updating, or managing features in this file.

### Feature Structure

Every feature **must** have these sections in this order:

| Section | Purpose |
|---------|---------|
| **Problem** | What's wrong or missing. 2-3 sentences max. No solution language. |
| **Prerequisites** | Other features or Linear issues that must be done first. Omit if none. |
| **Goal** | What the feature achieves for the user. 1-2 sentences. |
| **Design** | The meat: UX flows, behavior rules, UI details. Sub-sections vary by feature. |
| **Architecture** | Technical decisions: storage, APIs, state management. Omit if purely UI. |
| **Edge Cases** | Non-obvious scenarios and how to handle them. |
| **Implementation Order** | Numbered list of steps, ordered by dependency. |

### Writing Rules

- **Problem-focused.** Describe what's wrong, not how to fix it. The Design section handles solutions.
- **Concise.** Each section earns its space. If a section adds nothing beyond what's obvious, cut it.
- **No implementation code.** Reference file paths and patterns, but don't write code. That's for Linear issues and plan-implement.
- **User-facing language in Problem/Goal.** Technical details belong in Architecture.
- **Edge Cases are not Limitations.** Edge cases describe specific scenarios and their handling. Limitations are fundamental constraints (e.g., "requires network") - fold these into Architecture or Edge Cases.

### Identification

- Features use **stable slug IDs** as their heading anchor (e.g., `## Date Navigation` -> anchor `#date-navigation`).
- Slugs never change once a feature is added. This keeps external references (chats, notes, Linear issues) valid.
- Cross-references within this file use markdown links: `[Date Navigation](#date-navigation)`.

### Adding and Removing Features

- New features are appended before the Conventions section. Position in the contents table reflects rough priority.
- When a feature is fully moved to Linear (all issues created), **remove it** from the file. Do not leave stubs.
- When removing a feature, update the Contents table and fix any cross-references in remaining features. Use the plain feature name in prerequisite references - no Linear issue IDs, links, or "moved to" annotations. The backlog is always fully processed before pulling more features from this file.

### When to Move to Linear

A feature moves from this file to Linear Backlog when:
1. The design is detailed enough for `plan-backlog` to create implementation plans.
2. Prerequisites are done or in progress.
3. The feature is approved for implementation.

Move the **entire feature or a self-contained phase** - don't create Linear issues for half a feature while the other half stays here. When a feature is too large, split it into separate features first, then move them independently.

### Splitting Features

If a feature grows beyond ~60 lines or contains clearly independent phases:
1. Extract each phase into its own feature with a new heading.
2. Set prerequisites between them as needed.
3. Update the Contents table.

### Contents Table

The table at the top must stay in sync. When adding or removing features, update the table. Each row has: linked feature name and a one-sentence summary.
