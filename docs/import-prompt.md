# LifeOps — Brain-Dump → Import Prompt

Paste this into your LLM, fill in the two date lines and your brain dump, then paste the
returned JSON into **Import Tasks** in the app.

This prompt is kept in sync with the import schema the app actually parses
(`ImportParser.kt` / `ImportRepository.kt`). If you change those, update the prompt and the
**Schema reference** table at the bottom.

---

## The prompt

```text
You are a structured task planner for the LifeOps app. Given my weekly brain dump, extract
every actionable task and return ONLY a single valid JSON object — no markdown, no code
fences, no commentary, no trailing commas, double quotes only.

CONTEXT
Today's date: [YYYY-MM-DD]
Current week (Mon–Sun): [MONDAY YYYY-MM-DD] to [SUNDAY YYYY-MM-DD]
(Tasks always import into the current week. Infer due dates relative to today.)

MY ASPECTS (life domains) AND THEIR CATEGORIES
- Beacon: Sales, Dev, Admin, Marketing
- SWCA: Ops, Projects, Vendor, Admin
- Family: Kids, Health, Logistics
- Home: Maintenance, Projects, Admin
- Personal: Health, Finance, Learning

OUTPUT SHAPE
{
  "tasks": [
    {
      "title": "Send Q3 proposal to Austin at DataViz",
      "aspect": "Beacon",
      "category": "Sales",
      "priority": "high",
      "due_date": "2026-06-17",
      "hard_deadline": true,
      "estimated_minutes": 45,
      "status": "pending",
      "is_recurring": false,
      "notes": ["He asked for pricing tiers", "Follow up Thursday if no reply"]
    }
  ]
}

FIELD RULES (use these exact keys; the app ignores any other key and flags it as unknown)
- title — REQUIRED on every task. Make it specific and distinct; duplicate titles in the
  same week are skipped on import.
- aspect — one of my aspects above. Always include it: a category with no aspect is dropped.
  Match my existing names (case doesn't matter). Only invent a new aspect if nothing fits.
- category — a category under that aspect. Reuse an existing one when it fits; create a new
  one only if clearly needed. New aspects/categories appear in the import preview.
- priority — low | medium | high | critical. Default medium. Use high/critical for
  time-sensitive or high-stakes items.
- due_date — strict "YYYY-MM-DD" inside the current week. Anything not in that exact format
  is dropped, so never use relative words ("tomorrow", "Friday"). Default to mid-week if I
  gave no timing signal; use null for someday / recurring items with no date.
- hard_deadline — true ONLY if I explicitly named a deadline, meeting, appointment, or a
  specific date/time; otherwise false. A hard deadline adds a day-before reminder and is
  worth more points.
- estimated_minutes — realistic effort in whole minutes. This drives the task's point value,
  so estimate honestly (roughly 10 points per hour, more for higher priority / hard
  deadlines). Omit only if you genuinely can't guess (it then defaults to 60).
- status — pending (default). Use "completed" for things I said are already done (logged as
  history, earns 0 points) or "skipped" for things I've decided to drop.
- is_recurring — true for repeating/habit tasks ("every week", "daily"), else false.
- time_logged_minutes — optional; include only if I mention time already spent on the task.
- notes — array of strings capturing specifics: names, links, numbers, constraints. Use []
  or omit if none.

GENERAL
- Infer aspect, category, priority, and effort from context.
- One task per distinct action: split compound items, don't merge unrelated ones.
- Return only the JSON object — nothing before or after it.

MY BRAIN DUMP:
[PASTE YOUR BRAIN DUMP HERE]
```

---

## Schema reference (what the app actually reads)

The app parses a top-level `{ "tasks": [ ... ] }` object (a bare `[ ... ]` array also works).
Any other top-level keys — including `week_start` and `brain_dump_raw` — are **ignored**;
tasks always land in the current Monday–Sunday week.

| JSON key              | Type            | Default     | Notes |
|-----------------------|-----------------|-------------|-------|
| `title`               | string          | —           | **Required.** A missing title fails the whole import. Duplicate titles in the current week are skipped. |
| `aspect`              | string          | none        | Matched to an existing aspect case-insensitively; created if missing. |
| `category`            | string          | none        | Matched within the resolved aspect; created if missing. **Dropped if no `aspect` is given.** |
| `priority`            | string          | `medium`    | `low` / `medium` / `high` / `critical`; anything else → `medium`. |
| `due_date`            | string          | none        | Strict `YYYY-MM-DD`. Invalid/relative values are silently dropped. |
| `hard_deadline`       | boolean         | `false`     | Adds a day-before reminder; +0.25× point multiplier. |
| `status`              | string          | `pending`   | `pending` / `completed` / `skipped` (others exist but are lifecycle states). `completed` imports earn 0 points (historical). |
| `estimated_minutes`   | integer         | `60`        | Drives the point value (≈ minutes/6 up to 60 min, then 10 pts + 1/hr). |
| `time_logged_minutes` | integer         | none        | Logs a time entry on import if > 0. |
| `is_recurring`        | boolean         | `false`     | Marks the task as recurring. |
| `notes`               | string or array | `[]`        | Array → one note each; a single string also works. |
| anything else         | —               | —           | Captured and surfaced as **“Unknown fields”** in the preview; optionally appended as a note. |

**Point formula** (`ImportParser.computeResourceValue`): base = `minutes/6` for ≤ 60 min,
else `10 + extra hours`; urgency × `low 0.75 / medium 1.0 / high 1.25 / critical 1.5`;
`+0.25` if `hard_deadline`; minimum 1 point.
