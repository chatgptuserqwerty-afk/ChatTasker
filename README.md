# ChatTasker

Turns WhatsApp messages you pick into tasks, reminders and per-person notes.
Messages are held in memory only and discarded after you save; the app stores
nothing but your tasks, notes and settings. Reminders are the app's own
notifications (no calendar, no Clock alarms).

## Build and install

1. Install **Android Studio** (any recent version) on your PC.
2. Unzip this folder and open it in Android Studio (**File > Open**, pick the `ChatTasker` folder).
   Let it finish the Gradle sync. If it offers to upgrade the Android Gradle Plugin, accept.
3. On the phone: **Settings > About phone > tap Build number 7 times**, then
   **Developer options > USB debugging** on. Connect by USB and accept the prompt.
4. Press **Run** (green triangle). The app installs and opens.

To make a standalone APK instead: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**,
then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it
(allow "install unknown apps" for your file manager when asked).

## First run

- Allow notifications when asked.
- **Settings tab**: check "My WhatsApp name" (default `Narch`), pick ChatGPT or Gemini,
  and tap **Allow reminders in battery saver** so reminders fire on time.

## Daily use

1. In WhatsApp, long-press a message, tap more messages to select them, then **Copy**
   (or **Share > ChatTasker** where WhatsApp offers it).
2. Open ChatTasker > **Capture > Paste messages**. Repeat for other chats if you like.
3. **Analyse with ChatGPT** opens ChatGPT with the prompt filled in (it is also on the
   clipboard, so paste it if the field is empty). Press send.
4. Copy ChatGPT's reply, return to ChatTasker and tap **Paste AI reply**
   (or share the reply to ChatTasker).
5. Review: untick anything wrong, tap a row to edit, then **Save selected**.

**Quick tasks (no AI)** skips ChatGPT and spots dates, times and requests offline,
in English and Roman Urdu (aaj, kal, parson, subah, shaam, raat, 5 baje, Friday, 20 Oct...).

## Customising

- **Tasks**: type (My task / Waiting on), priority, due, reminder (at due, N minutes before,
  custom time, none), repeat (daily/weekly/monthly), tags, details. Filter, sort and search.
- **Settings**: default time for date-only tasks, reminder lead time, follow-up delay for
  "waiting on" tasks, snooze length, daily digest time, your tags, and extra rules
  for the AI (e.g. "Mark anything from clients as HIGH").
- **Backup**: export/import tasks and notes as JSON.

## Code map

| Path | What it does |
|---|---|
| `core/WhatsAppParser.kt` | Reads `[10/15, 8:29 PM] Name: text` lines (and exported-chat format) |
| `core/DateHints.kt` | Offline English / Roman Urdu date-time spotting |
| `core/Extraction.kt` | Prompt for ChatGPT/Gemini, parser for its reply, offline quick extractor |
| `data/Store.kt` | Tasks, notes and settings storage (JSON in app storage) |
| `reminders/Reminders.kt` | In-app reminders, Done/Snooze actions, daily digest, reboot restore |
| `ui/*` | Capture, Tasks, Notes and Settings screens |

If the AI reply ever stops importing, check the reply still uses
`TASK | ... ` / `NOTE | ...` lines; the prompt is in `core/Extraction.kt`.
