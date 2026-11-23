# AGENTS.md — Rules for every AI agent on Taptap

Read this whole file before doing anything. It applies to every agent and tool
(Claude Code, Codex, Cursor, Copilot, subagents). `CLAUDE.md` just points here.

## Project in one paragraph

Taptap is a personal, offline-first Android SOS app. Phones relay signed, encrypted SOS
messages to each other over Bluetooth/Wi-Fi (Google Nearby Connections) until one reaches
the internet and uploads them to a Supabase backend and a React responder dashboard;
signed ACKs flow back. **No LGU partnership, no business model, no SMS gateway** — the goal
is a working app. Read [PLAN.md](PLAN.md) for scope, [ARCHITECTURE.md](ARCHITECTURE.md) for
the protocol, [DESIGN.md](DESIGN.md) for UX.

---

## 1. Workflow rules (always)

### W1. Work is a FIFO queue
- Keep every request as a todo in a queue, in arrival order.
- New requests, tweaks and corrections from the user go to the **back** of the queue.
  Do not drop the current item to jump to the new one.
  - Exception: the user explicitly says "stop", "now", or "first", or the new message makes
    the current item wrong (e.g. "don't touch that file"). Then reorder and say so.
- Stop only when **every** item is in a final state: done, skipped (with why), or blocked
  (with what it waits on). Before ending, list the queue with each item's final state.

### W2. Read everything first, then edit, then test once
1. List every file you need for the task.
2. Read them all in **one pass** (parallel reads).
3. Make **all** edits.
4. Run the tests/checks **once** at the end. Re-run only what you fix.

Why: interleaving read→edit→test burns context and leaves files in half-edited states.

### W3. YAGNI: build only what the current milestone needs
- Build the smallest thing that meets the current milestone in PLAN.md §4.
- No new abstraction, dependency, config option or file unless the current task needs it now.
- Anything in PLAN.md §3.2 (out of scope) needs an explicit user request.

### W4. Suggest scope improvements, briefly and with a reason
At the end of a task, propose up to 3 scope improvements (additions **or** cuts). Each must
say *why*, with evidence (a risk ID, a metric, a bug seen), and roughly how big it is. Do not
build them unless asked.

### W5. Fresh-eyes review by a cheap subagent
The user authorises this standing rule. After any change that touches code, the protocol,
or more than one doc, start a subagent with the **cheapest available model** (e.g. Haiku)
and **minimal context**: give it only the diff (`git diff` or the changed files), this
AGENTS.md, and one line of intent. Ask it to list concrete problems (bugs, contradictions,
rule violations, missing tests) and **not to edit files**. Then fix or explicitly reject
each finding, with a reason. The reviewer does not apply W5 to itself (no recursive reviews).
Skip for typo-level edits.

### W6. Let the user grill the plan
For design or planning work, offer to run the `grilling` skill (or ask a short set of hard
questions yourself) so the user can stress-test the decision before it is built.

### W7. Mistakes: no apologies, find the cause in the instructions
- Do not apologise. Apologies don't fix anything.
- When something goes wrong, state: **(1)** what happened, **(2)** which instruction was
  missing, unclear or conflicting (in this file, PLAN.md, ARCHITECTURE.md, or the prompt),
  **(3)** the exact fix to that instruction, written as a diff or a new rule.
- Then fix the work itself.

---

## 2. Git rules

- **G1. Atomic commits.** Each commit does one thing and leaves the repo working. Split
  unrelated docs, refactors and features into separate commits. Exception: a code change
  and the doc update it requires (P5) go in the **same** commit.
- **G2. Conventional Commits.** `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`
  with an optional scope, e.g. `feat(mesh-core): add envelope codec`.
- **G3. No bulky files.** Never commit build outputs, APKs/AABs, `node_modules`, videos,
  design source files (`.psd`, `.ai`), archives, or any single file > 1 MB without asking.
  Check `git status` and `.gitignore` before every commit.
- **G4. No secrets.** No keys, `.env`, keystores, `google-services.json`, or the server
  private key. Use `.env.example` with placeholder values.
- **G5. Never push on your own.** `git push` (and force-push, tags, releases) only when the
  user asks in the current conversation. Committing locally is fine.
- **G6.** Don't rewrite published history. Don't use `--no-verify`.

---

## 3. Project rules

### Critical (never violate)
- **P1.** `mesh-core` has **zero Android imports**. All relay logic lives there, behind the
  `Transport`, `MessageStore`, `Clock`, `Crypto` interfaces. Why: the laptop simulator
  depends on it.
- **P2.** Never weaken envelope verification (signature, expiry, hop limit, rate limit) to
  make a test pass. Fix the test or the code.
- **P3.** The envelope must stay **< 300 bytes**. A codec test asserts the max size; never
  delete it.
- **P4.** The UI never shows "Delivered" or "Help is coming" without a verified signed
  RECEIPT/ACK (DESIGN.md §1, principle 2).
- **P5.** Protocol changes update ARCHITECTURE.md (and its decisions log) **in the same commit**.

### Important (follow unless justified)
- Kotlin: official style, explicit return types on public functions, no `!!` in `mesh-core`.
- Tests are deterministic: inject `Clock` and seeded `Random`; no real sleeps.
- Every user-facing string goes in resources for all three languages (en, fil, ceb).
- Prefer the standard library and platform APIs over new dependencies.

### Commands
_Not scaffolded yet._ When a module is created, add its file-scoped and full commands here
(test one file, test module, lint, build) in the same commit.

---

## 4. Definition of done

- [ ] Queue item fully done; nothing half-edited
- [ ] Tests/checks run once at the end and pass (or failures reported with output)
- [ ] Docs updated if behavior or protocol changed
- [ ] Fresh-eyes subagent review done and findings handled (W5)
- [ ] Atomic, conventional commits; nothing bulky or secret; **not pushed**
- [ ] Up to 3 justified scope suggestions given (W4)
