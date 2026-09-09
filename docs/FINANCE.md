# Finance — the picture of the household's money

Finance is the app that owns *what the institutions say*. The accounts, their balances, the
transactions behind them, the bills that come round, and the day each one falls due. It reaches USAA
(and most retail banks) through **Plaid**, and **Mercury** through Mercury's own API, and turns what
comes back into three answers: what you have, what's coming out, and the lowest your balance gets
before it does.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health, People, Project, Maintenance and Repository.

```
Operations Sandbox  →  Finance  →  Picture   (the forecast floor, net position, this month)
                               →  Due       (bills, by urgency)
                               →  Accounts  (the register, grouped by which way the money points)
                               →  Activity  (the ledger, and what comes round)
                               →  Connections (set-up, and the promises)
```

## The promise, stated first

This is the first module in the suite whose whole job is to talk to somebody else's server about
this household, so the usual promise — *nothing identifying leaves the device* — cannot be the one
made here. The promise Finance makes instead is narrower and worth stating exactly:

1. **Nothing leaves that you did not hand over.** The only credentials it holds are the ones typed
   into Connections: your own Plaid client id and secret, your own Mercury API token. It ships with
   none and discovers none, and it talks to exactly two hosts. That allow-list is a *tested
   function* (`logic/Endpoints.permits`), checked on every request, exact-match and TLS-only — it
   survives lookalike hosts (`production.plaid.com.attacker.net`) and userinfo authorities
   (`https://api.mercury.com@evil.example/`). No analytics, no crash reporter, no telemetry.
2. **It cannot move money.** Nothing under Plaid's `transfer`, `payment_initiation`, `signal` or
   `bank_transfer` surface is reachable from `data/net/PlaidClient` — not disabled, *absent*. Link
   tokens are minted for `transactions` and `liabilities` only, so the capability is not on the
   access token either. Mercury is asked for a read-only token, which is stronger still: it is
   enforced at Mercury's end rather than at ours.
3. **It never asks for a full account number.** Plaid's `auth` product would hand them over; this app
   has no use for them, so it does not ask. What arrives anyway (Mercury returns one on every
   account) is reduced to four digits by `logic/Masking.truncate` **at the parser**, before it can
   reach anything that writes. There is no column for more than four digits, so a backup of this app
   cannot leak an account number — the number was never in it.
4. **Nothing runs on its own.** No periodic worker, no push, no wake-up, and no notifications of its
   own. A refresh happens when you open the app and the picture is more than four hours old, or when
   you press Refresh. The reminder for a bill is a task on your LifeOps week, in the place you
   already look.

### Credentials on a phone, and the trade being made

Plaid's own guidance is that a client secret belongs on a server, and that guidance is correct for an
application with many users. This suite has exactly one user, no server, and no intention of
acquiring either. So the household's own Plaid credentials live on the household's own phone, in
`EncryptedSharedPreferences` behind a keystore key.

That is a deliberate departure and the app says so on the Connections screen rather than hoping
nobody asks. What it buys is the thing the whole suite exists for: nothing about this household's
money passes through a server belonging to whoever wrote this app, because there isn't one.

### What the backup deliberately does not carry

`FinanceBackupContributor` copies `finance.db` and the `finance_*` preference files. It does **not**
copy any credential: the Plaid secret, the access tokens, the Mercury token and the sync cursors live
in `secure_finance_access`, a file whose name deliberately does not start with `finance` — so the
contributor's prefix filter cannot pick it up even by accident. The exclusion is a property of the
name rather than of a filter somebody could relax in a later commit.

The cost is real and is worth paying: **restore a backup onto a new phone and you reconnect.**
Accounts, balances, years of transactions and every bill come back; the ability to fetch new ones
does not, until somebody signs in again. Two minutes, against a zip file in a cloud drive that would
otherwise carry a standing read grant on a bank account — one that cannot be rotated by changing a
password, and whose escape the household would have no way of noticing.

The cursor is left behind for a related reason: restored beside a token that is gone, it would tell
the *next* connection to start where the *old* one left off, silently skipping everything between.

## The two providers are not interchangeable

`logic/Provider` says so out loud, because the difference shows up on screen and hiding it would mean
either concealing a limitation or inventing a due date.

| | Plaid | Mercury |
|---|---|---|
| Reaches | USAA and ~12,000 institutions | Mercury only |
| Statement due dates | **Yes** — `/liabilities/get` | No. Every Mercury bill is predicted. |
| Read-only enforced by | which products were requested | the token itself |
| Can fall out of authorisation | yes, routinely | no |
| Sign-in | Hosted Link, in a browser | paste a token |

Plaid brings statement due dates, which is the single most valuable thing in this app, and it brings
re-authentication: a bank can decide at any time that you should sign in again. Those come together;
neither is optional.

### Why Hosted Link rather than Plaid's SDK

Plaid's Android SDK is a large third-party dependency that opens its own activity, into a suite whose
discipline is framework-free logic and one `HttpURLConnection` per provider. Hosted Link needs
neither: the person signs in on a page Plaid hosts, opened in **a browser rather than a WebView**,
and the app asks `/link/token/get` afterwards what came of it.

The browser is a security decision, not a convenience. A WebView is a window this app could read the
contents of, and a bank password should be typed somewhere this app demonstrably cannot see. The
browser also shows the address bar and the padlock, which is how you check you are on Plaid's page
and not a copy of it. Hosted Link also removes the redirect the SDK route needs — there is no App
Link to register and no URL carrying a token back into this process where another installed app
could race for it.

## The two sign problems

Both are the sort of bug that produces a screen full of confident, wrong numbers, so both are solved
once, in a tested place, and never mentioned again.

**Balances.** Every institution reports a credit card balance as a *positive* number, because from
the issuer's point of view you owe them $840 and 840 is positive. Add that to a checking balance and
the household is richer for having a credit card. So: **a balance is stored exactly as the
institution reports it** — the number you can check against your bank's app — and the *direction*
lives on the kind (`AccountKind.owed`). Only `Accounts.netPosition` combines them, and it asks the
kind which way the money points. A card in credit (you overpaid it) is counted as an asset rather
than as a negative debt, which would understate both sides at once.

**Transactions.** Plaid reports an amount as positive when money *leaves*; Mercury does the opposite,
and so does every bank statement ever printed. The parsers pick one convention at the boundary:

> **`amountCents` is signed the way a statement reads it: negative is money out.**

`PlaidJson` negates on the way in; `MercuryJson` does not need to, and the absence of a flip there is
deliberate rather than an oversight.

## What is due, and how much of it the app actually knows

A bill comes from one of three places and they are **not** equally trustworthy. Every bill carries its
`source` and the Due screen shows it — on all of them, not only the uncertain ones, because a badge
that appears only when something is doubtful is a badge people learn to skip.

| Source | Means |
|---|---|
| **From your statement** | The institution said so. A card's `next_payment_due_date`, a mortgage servicer's next payment. A fact with a date on it. |
| **You entered this** | Typed in. As trustworthy as the person who typed it, which is usually the most trustworthy thing here. |
| **Predicted from your history** | `logic/Recurring` found a pattern and stepped it forward. A good guess about a real obligation whose exact date and amount are this app's arithmetic. |

A card's bill is published as the **statement balance**, with the minimum shown beside it rather than
instead of it. Paying the minimum is how a balance becomes permanent, and an app that leads with the
minimum is quietly recommending that.

### Finding the things that come round

Nothing about `logic/Recurring` is machine learning. It is a group-by, a median, and three thresholds
that were each chosen for a stated reason:

- **Three occurrences, not two.** Two charges a month apart are a coincidence often enough to matter,
  and a false positive here is not cosmetic — it puts a task on somebody's LifeOps week for a bill
  that does not exist.
- **Gaps within four days** of the cadence (more for quarterly and yearly, which move about between
  cycles). Monthly bills land on the same date, which means gaps of 28 to 31 days, and a weekend
  shift adds two more.
- **Amounts within 20% of the median.** A subscription is the same every time; a heating bill never
  is. Both should be found, and a $4 coffee should not join a $60 dinner at the same café.

**Amounts are filtered before the cadence is examined**, and that order is the interesting decision.
The obvious way round — establish the rhythm, then check the amounts — fails on a case that is not
exotic at all: a gym charged $45 monthly that also takes $180 once a year. The extra charge splits
one 30-day gap into a 15 and a 16, and a cadence check run first throws away a series that is plainly
monthly.

It also **never detects income**, which is the decision that makes the forecast worth reading.

Matching is done on a *merchant key* (`logic/Merchants.key`) rather than on the description, because
bank descriptions for the same monthly charge are not stable strings: `CITY UTILITIES 0423 AUTOPAY`
one month and `CITY UTILITIES 0524 AUTOPAY` the next. Digits go entirely — reference numbers, store
numbers, embedded dates — and the payment-network prefixes (`SQ *`, `POS DEBIT `, `ACH DEBIT `) that
say how a charge was routed rather than who was paid.

### A bill is settled by the bank, not by a button

There is no "mark as paid" on the Due screen, deliberately. A bill is marked paid when **a matching
payment turns up in the account** (`Bills.settle` matches payee, then a seven-day window, then the
amount, and each payment is consumed by at most one bill), or when its LifeOps task is ticked. Both
are facts. A button would let the list claim a bill was paid when it wasn't, which is the one thing a
screen like this must never be able to do.

Paid bills stay on the list rather than vanishing. A list that empties itself as the month goes on
looks like the app forgot, and "the insurance went out on the 3rd" is exactly as useful as "the
insurance goes out on the 3rd".

## The forecast is a floor, not a prediction

The Picture screen leads with one sentence — *"At worst $412 in 6 days, before anything else comes
in"* — and that wording is as load bearing as the arithmetic behind it.

`logic/Forecast` projects bills (they have dates) and ordinary daily spending (a flat rate from the
last complete month). It **refuses to project income**, and that refusal is the whole point. Payroll
is regular and would be found by exactly the code that finds bills. Projecting it would produce a
much prettier line — one that dips towards the 1st and recovers on the 15th, and never shows a
problem — but a projected paycheque is a promise about somebody's employer, and the cost of being
wrong lands on the household rather than on the app.

So the line only ever falls, and the trough it finds is a floor: **the real balance on that day will
be this or better, never worse.** A forecast you can say that sentence about is worth having; one
that averages out to roughly right is not.

Two details follow from taking that seriously:

- The burn rate comes from the last *complete* month, never from the current one. A rate taken on the
  3rd is three days of spending divided by three days, which on a month that opened with the rent in
  it projects a household into destitution by Friday.
- An overdue bill lands on today rather than being dropped. It is still money that has to go out, and
  dropping it would show cash the household has already committed.

`FinancePrefs.floorCents` turns the alarm into something more useful than the overdraft: "you'll dip
under your $500 buffer on the 28th" can be acted on a week early, where "you'll be overdrawn on the
28th" is news that arrives too late.

## The LifeOps week

Finance knows *when* money is due; LifeOps is where a week is planned. So a bill does not grow a
notification here — it **publishes itself onto the LifeOps week** as a task dated the day it falls
due, ten days ahead, and LifeOps parks it in Future Tasks until that week opens. This is the seam
Maintenance already publishes upkeep on, and the argument is the same: one planner for the suite, and
this app supplying it rather than competing with it.

The task's title carries the figure, because a week is read as a list of one-liners and "Pay USAA
Visa" is a different decision from "Pay USAA Visa — $1,240". A predicted bill says "about", so the
week never quotes a guess as though a biller had sent it.

Where this differs from Maintenance's round is worth knowing:

- **Maintenance's plans are cycles.** Tick the oil change and the clock restarts, so a completion is
  followed by publishing the next occurrence. A bill is one obligation on one date; the recurrence
  lives a level up, in `Recurring`, which mints next month's bill when next month's charge appears.
  So the round is single-pass — there is nothing a second pass could discover.
- **A bill can be settled without anybody ticking anything.** When a matching payment lands, the task
  LifeOps is still holding is asking for something already done, so the round takes it off. That is
  the behaviour that makes the app worth having on the week at all: the list cleans itself up from
  the account rather than from your memory.

Everything else is the same reconciliation, and for the same reasons: a task you deleted is not put
back (the published date is remembered even after the link is dropped, which is what stops it), a
task carried into a new week under a new id is followed, a tick outranks everything, and two bills
cannot share one task — the loser of that race goes without one, because a single tick must not pay
two bills.

Which aspect these tasks are filed under is **LifeOps' decision, not this app's**:
`Settings → Finance bills` in LifeOps, read at publish time, so changing it re-files what arrives
from then on and never touches a week already planned — including anything you moved by hand.

## Where Finance ends and Maintenance begins

There is a real overlap and it is resolved by asking *whose fact is it*.

**Maintenance holds what is owed against a thing you own.** The mortgage is an obligation attached to
the house, sitting beside what the house is worth, when the furnace was last serviced, and eleven
years of what it cost. That is the register of your property.

**Finance holds what the bank says about the money.** The account the mortgage is paid from, the
balance in it this morning, and the servicer's statement saying $2,140 falls due on the 14th.

Both may mention the same mortgage, and neither is the wrong place for it: one is the debt against an
asset, the other is a payment leaving an account on a date. What they must not do is both put it on
your week, and they don't — Maintenance publishes *upkeep*, Finance publishes *bills*, and a mortgage
payment is only ever the second.

Like Project and Maintenance, Finance is **not on the suite's sync spine**. People replicates because
two apps genuinely write the same person; nothing else in the suite writes into a balance, so there
is nothing to reconcile and no `syncVersion` column in the schema. It does depend on `:repository`,
one way, because the paperwork money arrives with — the statement, the payoff letter, the 1099 — is a
document the household filed rather than something a finance app should keep a second copy of.

## Getting set up

**Mercury** is the easy one. In Mercury: `Settings → API tokens`, make a token with **read-only**
access, and paste it into `Connections → Add Mercury`. No Plaid account is involved.

**Plaid** needs a developer account — yours, free for personal use:

1. Sign up at plaid.com and find your **client id** and **secret** in the dashboard.
2. In Finance: `Connections → Plaid keys → Add`. Choose **Sandbox** first — it uses Plaid's fake test
   bank, and a wrong key against production just fails confusingly.
3. `Add a bank` opens a Plaid-hosted page in your browser. Sign in there and come back; the app
   notices when you're done.
4. Once the sandbox flow works, switch the environment to **Production** and re-enter the production
   secret. Production access for personal use is requested through Plaid's dashboard.

When a bank later asks you to sign in again, the connection says so on the Connections screen in the
ordinary accent rather than in red — it is a thing to do, not a thing that broke — and everything
already fetched stays exactly as good as it was.

## The code

Everything that decides anything is framework-free and unit-tested on the JVM. `finance/logic/` has
no Android in it and no network, which is what lets 150-odd tests run the real code over captured
payload shapes rather than a screen being squinted at on a phone.

| File | What it decides |
|---|---|
| `logic/Accounts` | The sign rule, the kinds, and the net position |
| `logic/Transactions` | The other sign rule, the category map, the merchant key |
| `logic/Recurring` | What counts as a series, and when the next one is due |
| `logic/Bills` | Prediction, de-duplication against statements, settlement |
| `logic/CashFlow` | Roll-ups that refuse to double-count transfers and card payments |
| `logic/Forecast` | The floor, and the day it happens |
| `logic/PlaidJson`, `logic/MercuryJson` | Parsing, the sign flip, and truncation at the boundary |
| `logic/Endpoints` | The two-host allow-list, and dollars to cents without losing one |
| `logic/Masking` | Four digits, and never more |
| `logic/BillTasks`, `logic/BillRound` | The LifeOps seam |

The Android half is deliberately thin: `data/net/` is two HTTP clients, `data/db/` is four tables,
`data/repository/` is the mapping and the refresh, and `ui/` renders what the logic already worked
out.
