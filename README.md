# LifeOps

A single-user, offline-first **weekly operations log** for your life — part task
manager, part time tracker, part honest mirror. You plan a week, do the work, log
the hours, and close the week. Completed work earns **resources**; every week is
permanently recorded as one ring in a **Growth Record** that can't be faked, ground,
or back-dated.

LifeOps is opinionated and a little sardonic. It is not a gentle habit app — it keeps
the receipts.

> **New here?** The full end-user manual is **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** —
> written for someone who just installed the app and wants to know how to use it.

> **Citation** (the companion reading app) is a separate module and a peer on the sync spine —
> see **[docs/CITATION.md](docs/CITATION.md)**. Its framework-independent core lives in `:core`
> (pure JVM, unit-tested); the Android reader is `:citation`.

> **Operations Sandbox** is the container these apps now ship inside — it's the `:app` module, the
> single installed application and the central hub the whole suite opens through. It opens on a
> **phone-style home screen**: a tile per app in that app's own hand-drawn mark and colour — no two
> share a silhouette, so a tile is recognisable before its colour registers — over a dock holding
> the gear and the backups, with a **weather tile** under the clock for wherever the phone is. The
> grid is **yours**: the gear rearranges it, hides the apps you never open, and can give any of them
> an icon of its own on your *phone's* home screen — and long-pressing the suite's launcher icon now
> offers the apps you opened most recently. One launcher that opens LifeOps (`:lifeops`, the standard app), Citation
> (`:citation`), Logistics (`:logistics`), Advisor (`:advisor`), Health (`:health`), People
> (`:people`), Project (`:project`), Maintenance (`:maintenance`), Finance (`:finance`), Repository
> (`:repository`), Secrets (`:secrets`) or Utilities (`:utilities`); one place to back the whole suite up into a single `.zip` and restore from it —
> or to have that same archive **uploaded to your own Azure storage container on a schedule**, on
> Wi-Fi, keeping the newest few, so the backup that saves you is the one nobody had to remember to
> take; and one
> place that decides what all of them **look** like — a shared preset and light/dark mode, plus
> an accent per app, applied by every hosted screen, and a **wallpaper** for its own home screen
> (a shipped design, your own gradient, or the suite's colours). LifeOps, Citation, Logistics, Advisor, Health,
> People, Project, Maintenance, Finance, Repository, Secrets and Utilities are library modules hosted in that one process —
> see **[docs/OPERATIONS_SANDBOX.md](docs/OPERATIONS_SANDBOX.md)**. The backup format/engine is the
> pure-JVM, unit-tested `:backupkit`; the appearance contract is the pure-JVM, unit-tested
> `:suitekit`, with its Compose theme in `:suiteui`.

> **Advisor** (the private, on-device assistant) is a peer module — see **[docs/ADVISOR.md](docs/ADVISOR.md)**.
> It's the suite's **RAG** layer: it answers questions grounded in your own data across LifeOps,
> Citation, Logistics, Health and People, under an explicit **per-app permission gate** (denied by default). It also
> keeps **identity-based data** as a portable JSON file, a set of **standing named profiles** (user,
> LLM persona, projects) it references by name and can write to via a `@remember` directive, and a
> **dedicated, heavily-tagged long-term memory** database it recalls from. A **unifying engine (C3A)**
> coordinates all of it and decides whether to answer, ask, or flag a missing source — on the rule
> *"not knowing is acceptable; being wrong without asking is not"*, so when it's unsure it asks
> instead of guessing. The
> language model is a local **Qwen3-4B** (Q4_K_M GGUF) run on-device via llama.cpp, wired in behind a
> single `LocalLlmEngine` seam; until the weights are dropped onto the device it falls back to a
> deterministic, grounded placeholder, so Advisor works either way. The retrieval, permissions, recall
> and prompt assembly around it are real and JVM-unit-tested in `advisor/logic/`. It requests no
> `INTERNET`; nothing leaves the device.

> **People** (the household directory) is a peer module — see **[docs/PEOPLE.md](docs/PEOPLE.md)**.
> It owns *who*: the roster, how to reach someone, the dates that come round, the notes you keep
> about them, and a **daily check-in** — a small form you write for one person and answer once a day
> ("Lunch", "Enjoyed", "How the day went"), with the questions, their kinds and their order all
> yours. A question you rename is renamed on every day it already recorded; one you take off the form
> keeps its answers rather than taking six months of lunches with it; and a day exists only if it
> says something, so an untouched form records nothing. Check-ins stay in People — they ride neither
> the sync seam nor a partner pairing — and it keeps **LifeOps in step over a two-way sync seam**, the same mailbox spine
> Citation rides. Both apps can edit the same person (LifeOps mints them from calendar attendees;
> you type birth dates into People), so the roster is **replicated rather than borrowed**: each peer
> keeps its own rows and they reconcile. **Health is on the seam too**, and takes only the people
> ticked as **household members** in the directory: tick somebody and Health grows a profile for
> them with their birth date, which is exactly what its age-aware fever rules need; leave them
> unticked and it never hears about them. Un-ticking stops Health being offered them and never
> deletes what it already recorded. The merge rule is *newer wins field by field, but a blank never beats a value* —
> record-level last-write-wins would let whichever app you touched last erase the other's half of
> the person. LifeOps' `persons` table and all five foreign keys into it were
> left exactly as they were; the migration that made it a peer is purely additive. The contract —
> packet, binder, merge, engine — is pure JVM in `people/sync/` and unit-tested, including a full
> two-peer round.
>
> People also carries a **second, separate seam: partner sync**, which pairs two *different*
> households by QR code. Each person scans the other's code — one scan yields half a secret, and the
> token that gates the seam needs both, so the connection is two-way by construction and an install
> that was never scanned can address nobody. Once paired, **People → a person → View LifeOps** shows
> that partner's current week: their real LifeOps tasks, ticked as they tick them, mirrored into
> People's own tables and shown on their own screen. It is **never merged into your LifeOps** — not
> your week, not your aspects, not your capacity. Exactly one thing crosses into a planner, and only
> because somebody asked for it by name: a task you add *to their* week, which becomes a real task on
> theirs (and is taken once, for good). Rounds run on app open and on demand — People's roster has a
> **Partner sync** card that sets the seam up on a household that has never paired (identity,
> exchange folder, first envelope) and runs a round on request, and each person's page has the same
> button for the moment mid-handshake when you need to know whether they have scanned yet — so what a
> partner changed is kept and reported the next time you look. Sharing expires by itself — the seam
> only ever publishes the current week.

> **Health** (the household health tracker) is a peer module — see **[docs/HEALTH.md](docs/HEALTH.md)**.
> It keeps a **profile per person** and, against each of them, the temperatures and other readings
> taken, the symptoms they've got, the medicines they're on with the spacing and daily limits from
> the label, and the **illnesses** all of it hangs off — so "when was the last dose", "is this higher
> than the last one" and "which day did the fever start" are already answered rather than
> reconstructed at 3am — and an illness reads back two ways: a summary of how it went, and a
> **history** of everything that was done, hour by hour. Anything that wasn't recorded at the time can
> be added later, including a whole illness that has already been and gone; records are filed by *when
> they happened*, and the ones written up from memory say so.
>
> Its Meds tab is a **medicine cabinet**: the household's actual stock, what is
> expired or running low, and — looked up from **RxNorm and openFDA** — what each product is made of
> and what its label says, shown beside the dose rules you typed in rather than instead of them. It
> also **reminds you**, either at set times or when the next dose is due.
>
> Its Care tab covers **who pays for this and who do we take her to**. Insurance is stored as *the
> card*, not the policy — the insurer, each person's member number on the household's plan, the
> numbers on the back, and photographs of it saved when you attach them, so Health can produce **a
> card-sized PDF on demand** for a reception desk or a school form. There is deliberately no field for
> a copay or a deductible: those are an eighty-page contract, and a box to type one into is an
> invitation to plan around a number nobody checked. Doctors are kept **separately from the
> insurance** — the plan changes every January and the paediatrician doesn't — and Health can check
> each of them against the insurer's own **published provider directory** (the public FHIR endpoints
> payers publish under the CMS interoperability rule). Every check is kept rather than overwritten,
> which is what lets it say *"listed in March's directory, not in today's"* — a doctor who has left
> the network — as against *"never listed"*, and to say *"couldn't tell them apart"* rather than
> guessing between two people with the same surname.
>
>
> Health has **no household screen of its own**: no People tab, no add-a-person form, no
> remove-a-person button. The household belongs to the People app and Health is a peer on its sync
> seam — a second place to add or rename somebody would be a second answer to "who lives here". The
> profile bar switches between people on every tab, and its last chip opens People for anything else.
> What Health *does* own about a person — their usual temperature and their medical note, neither of
> which is ever published — lives on its **Information** tab, renamed from Illness because the
> question asked far more often than "is anyone ill right now" is **what does normal look like for
> this person**: a 37.6 means one thing for somebody who runs at 36.4 and another for somebody who
> runs at 37.1. That tab now reads as who they are, what their normal is, how temperatures are shown,
> and then the illnesses.
>
> Its Record tab holds **what is true about a person between illnesses**. Allergies and conditions are
> now rows rather than a sentence in a free-text note — which means an allergy can be listed, ordered
> by how badly it went last time, and **checked against a medicine as you add it**. That check matches
> what you wrote down against what the label says and nothing else: it will not tell you that
> penicillin and amoxicillin are relatives, because that is pharmacology and this app is no more
> qualified to do it than to read a dose off a label — and **no warning is never an all-clear**, which
> every surface says rather than showing a reassuring tick. Conditions are deliberately *not* illness
> episodes: an episode has an end, and asthma doesn't. The tab also holds the **vaccination record** —
> the card in the drawer, typed up, reported as what is *recorded* and never as "up to date", because
> Health ships no schedule and a schedule varies by country, birth year and risk group — and the
> **paperwork**: after-visit summaries, lab results, referral letters, school forms, stored exactly as
> they arrived and **never read**. Nothing is parsed out of a document; that is a separate feature and
> belongs to the change that owns it.
>
> The judgements it makes — whether a reading is a fever, given where it was taken and how old the
> person is; whether the next dose is due yet under both the interval and a rolling 24-hour allowance;
>  whether a bottle is out of date or out of doses; where a doctor stands against a plan, read out of
> every check ever made; whether a medicine matches something somebody is allergic to — live in
> `health/logic/` and are JVM-unit-tested. **No record ever leaves the
> device**: the drug lookup asks what a medicine *is*, never who takes it, and the directory check
> asks about a *doctor*, never about anybody in the household — no member number, no profile, ever.
> That is the line it holds. It records; it does not give medical advice.

> **Project** (the document and planning repository) is a peer module — see **[docs/PROJECT.md](docs/PROJECT.md)**.
> It owns *the work you are making*: a shelf of projects, and inside each one five ways of looking at
> the same thing — the **Outline** it is shaped by, the **Docs** it is written in, the **Lore** it has
> to stay consistent with, the **Timeline** it happens on, and the **Board** it gets built through.
> Notion's blocks, Reedsy's manuscript outline and a kanban board, in one place, because they are
> five views of one project rather than five apps.
>
> The outline's rows are *parts of the work* — they nest, they carry a status through drafting and
> revision, and they carry a **length**: words roll up from the leaves, so an act's number is the sum
> of its scenes and nobody maintains it. **Cut** is a status rather than a delete, because the scene
> you cut in August is the one you want back in October. Documents are **blocks**, and the
> interchange in both directions is **Markdown** — paste a chapter in, get it back out — with the
> word count taken over prose only, so a pasted config file cannot inflate it. Link a document to a
> scene and that scene's length becomes the document's, kept in step on every edit; the outline stops
> being a plan you maintain beside the writing and becomes a view of it.
>
> Lore links with `[[double brackets]]` and **only** with those — nothing is linked because a word
> appeared in a sentence — and it reports the two things a wiki knows and you don't: **backlinks**
> (where an entry is spoken of, collected without anyone recording them) and **broken links** (every
> name the project referred to and never wrote down, which is the list of pages worth writing next).
> An ambiguous name resolves to *nothing* rather than to whichever row came first. On the timeline,
> the author's order is the timeline and the "when" is a note about it — free text, read as a date,
> year, day or chapter where it can be — so the app can say **"you put the coronation before the
> battle and dated it after"**, which is the error a story timeline exists to catch and is only
> catchable because the two are kept apart. The board's **WIP limits warn and never refuse** (a board
> that rejects the card in your hand teaches you to lie to it), and deleting a column **keeps its
> cards**, stranded on purpose, with a banner to re-file them.
>
> Two edits can throw a whole document away in one tap — pasting Markdown over it, and rebuilding
> its flattened tables — and what a project holds may be the only copy of that writing anywhere. So
> a **version is kept first**, automatically, and the menu offers to keep one by hand before a
> rewrite the app cannot see coming. The history says *why* each was kept rather than only when,
> because a column of timestamps is not something anybody can choose from; **restoring keeps the
> current text first**, so going back is itself undoable. A version stores its **blocks**, not
> rendered Markdown — the Markdown round trip is the export format and drops an empty paragraph,
> renumbers a list and reads a paragraph beginning `- ` as a list item, all of which are fine when
> exporting and none of which are acceptable in the copy you restore from. The last twenty are kept.
>
> Two things tie the sections into one app rather than five. **Compile** walks the whole outline,
> pulls in every document linked to it, and hands you the manuscript — and it reports the holes
> rather than hiding them: pieces with nothing written are listed by name, and documents belonging to
> no piece are counted even when excluded, because "12 documents are not in this export" is the
> sentence that saves you. **Search** covers all five sections at once, matching every term against
> the whole record rather than title-and-body separately (so "kestrel smuggler" finds the entry whose
> name is in one and description in the other), ranked title-before-body and fully deterministic.
>
> Anything in the suite can **open Project at a place** rather than merely starting it — a project, a
> named section of one, or a document in the editor with its project underneath it on the back stack
> — through one intent extra whose addresses are the app's own routes. A link naming something since
> deleted lands on the shelf rather than on an editor for nothing, and there is no URL scheme: the
> suite shares a process, and an app holding writing that never leaves the device has no reason to
> let every app on the phone address its rows.
>
> A board card can carry a **due date**, and that is the only date in the app. The line Project holds
> is finer than "no dates": when a thing is *due* is a fact about the work, and Maintenance keeps the
> same kind of fact about a furnace; when you will *do* it is a decision about your time, and that is
> LifeOps' to make. So there is no agenda here, no calendar, no today screen and no lane that sorts
> itself by date — a dated card looks like any other but for the chip saying when it is due. A
> finished card is never late however late it was, and a date that has already gone is remarked on
> rather than refused, because people write down deadlines they have missed.
>
> A dated card **hands itself to the LifeOps week** — a task on the day it falls due, ticked in
> either place and finished in both. That is the same one-way seam Maintenance uses (`:project ->
> :lifeops`, plus the bus LifeOps announces completions on), and it is a reconciliation rather than
> an event handler: a round runs on a tick, on opening Project and after every card edit, and
> reaches the same answer each time, so a missed announcement costs latency and never correctness.
> Publishing **adopts** an open task of the same title rather than adding a second beside it — if
> you already wrote that row by hand, it is the job. Ticking it in LifeOps moves the card to the
> board's finished column; dragging it there takes the task off the week.
>
> A project's **files** — the brief, the contract, the reference PDFs — are documents the household
> filed rather than writing, so they live on the suite's shelf and are shown here in place. They can
> be filed on the project, a piece of the outline, a lore entry or a card, so a photograph for one
> scene and the contract for one piece of work no longer land in the same pile. Because the shelf
> holds a *label* rather than a foreign key, a rename is pushed down to it — renaming a project
> renames every drawer in it — and a delete names every record, since a project's rows cascade and
> nothing is left afterwards to say which drawers were its.
>
> It is also the **second app in the suite to serve connection routes** — `/v1/Project/local/…`, on
> the same addressing scheme LifeOps uses and the `application` segment that scheme always reserved
> for a peer. The routes can add and organise a project and cannot rewrite a word of what is already
> written: nothing appends to a document, replaces one, edits a block or deletes a subtree, because
> the caller is a sentence relayed by Advisor and a misheard word must not be able to destroy writing
> with no second copy. A name that matches two projects resolves to neither and asks for an id.
>
> Project is **not on the sync spine**: nothing else in the suite writes into a
> project, so there is nothing to reconcile. The tree walks, the Markdown round trip, the wiki index,
> the timeline reading, the board moves, the compile, the search, the rules about which versions of a
> document are worth keeping and the addresses that say where in Project to open are pure JVM in
> `project/logic/` and covered by 206 unit tests. The store beneath them — the cascades, the soft
> links, the word-count roll-up, the versions, a linked address checked against what is actually
> there, and the schema's upgrade path — has 69 of its own, run against a real database on the JVM. It requests no permissions and has no `INTERNET`; nothing it holds
> leaves the device.

> **Maintenance** (the asset and upkeep register) is a peer module — see **[docs/MAINTENANCE.md](docs/MAINTENANCE.md)**.
> It owns *the things you own*: the house, the cars, the furnace, the mower — what each one **is**,
> what is **owed** on it, what it **costs** to keep, and what it **next needs done**. The shape is
> borrowed from [Homi](https://github.com/Yuss9/homi) and then narrowed hard: no accounts, no roles,
> no invitations, no object store — none of that is purpose-built for one person on one device inside
> an app that already ships as a single install.
>
> Kind-specific fields are **declared as data, not as columns**: a vehicle asks for the whole of the
> paperwork (VIN, trim, body style, engine, fuel, transmission, drivetrain, colour, plate, where it is
> registered, tyre size and oil spec); a home asks for an address, a year built and a parcel number;
> an appliance asks for a serial number and where it lives. Adding a kind is authoring — one entry in
> `logic/AssetKind` grows its own fields, already validated, everywhere they are shown — and the add
> dialog and the edit dialog draw from that one list, so **you are asked for everything the moment you
> add the thing**, with nothing but the name required. A **meter belongs to a kind** too, so mileage
> intervals are only offered where there is an odometer to measure them against.
>
> Schedules carry **either or both** intervals — *"every 5,000 miles or 6 months, whichever comes
> first"*, the way an owner's manual writes it — and the app says which leg won. Two odometer readings
> give a **rate**, and a rate turns 5,000 miles into a date; one reading gives no date rather than a
> guessed one. A mileage interval with no baseline says *"log one service to start the clock"* instead
> of quietly granting itself a free 5,000 miles. **Logging the work is the only thing that moves a
> clock** — there is no silent reset, which is why the history has no holes in it.
>
> Costs are asked **sideways as well as down**: a third tab totals what the whole register cost over
> the last year or ever, ranks the things by what they ate, and says who has been paid — the question
> "who did the brakes last time" only has an answer across assets. What you have sold still counts as
> spend (history includes the truck you had until March) but not as worth or owed. And an asset's
> history **leaves as CSV** through the system file picker, because a service history is worth money
> on exactly one day and on that day a whole-suite backup is no use to the buyer.
>
> The **mortgage** is typed as the note reads (principal, rate, term, first payment) and everything
> else is derived: balance today, principal and interest paid, payoff month — or *never*, when the
> payment doesn't cover the interest — and equity, negative when it is. There is deliberately **no
> stored balance**, which is the field that makes every other app's mortgage page wrong within a
> month; rates are basis points and money is whole cents, so nothing drifts. **VINs are checked
> offline** by their own check digit, and a digit that disagrees is *reported, not rejected* — plenty
> of vehicles built outside North America carry a valid VIN that fails that arithmetic. Costs refuse
> to annualise a history shorter than a year.
>
> Upkeep goes **on the LifeOps week**. A plan publishes itself as a task dated the day it falls due
> — `Truck: Oil change`, 30 May — which LifeOps parks in its **Future Tasks** queue and wakes into the
> week that contains that date, so a service five months out lands in the right week five months out.
> **Tick it in LifeOps and the tick comes back**: the service is logged here, the clock restarts from
> the completion, and the next occurrence goes on the week. Never as a *recurring* LifeOps task —
> that would put two engines in charge of when the next oil change is — and never with a hard
> deadline, which would bin the job at week close. It is a per-plan switch, on by default. **Which
> aspect those tasks are filed under is LifeOps' call, not Maintenance's** — `Settings → Maintenance
> upkeep`, the same arrangement Citation's reading time has — read at publish, so changing it re-files
> what goes on the week from then on and leaves anything you moved by hand exactly where you put it.
>
> The seam is a **reconciliation, not an event handler**: LifeOps announces the tick as it happens
> (one small outbound bus in its connection layer), but the same round also runs when Maintenance
> comes to the foreground and after every edit, and reaches the same answer. So the awkward cases are
> ordinary — a task you deleted stays deleted until the plan moves on, one carried into a new week is
> *followed* rather than duplicated, one stranded in a closed week is put on this one, a job you had
> already written by hand is **adopted** rather than duplicated, and a plan you paused takes its task
> off the week. Maintenance raises **no notifications of its own**; deciding
> what today looks like stays LifeOps' job.
>

> **Finance** (the picture of the household's money) is a peer module — see **[docs/FINANCE.md](docs/FINANCE.md)**.
> It owns *what the institutions say*: the accounts, their balances, the transactions behind them,
> the bills that come round and the day each one falls due. It reaches USAA — and most retail banks —
> through **Plaid**, and **Mercury** through Mercury's own API, using credentials the household types
> in itself.
>
> The promise is narrower than the rest of the suite's and is stated on the Connections screen rather
> than left to be discovered: **it reads, and only reads.** Nothing under Plaid's `transfer`,
> `payment_initiation`, `signal` or `bank_transfer` surface exists anywhere in the module — not
> switched off, *absent* — and link tokens are minted for `transactions` and `liabilities` only, so
> the capability is not on the access token either. Mercury is asked for a read-only token, which is
> stronger still: enforced at Mercury's end rather than at ours. It talks to **two hosts and no
> others**, and that allow-list is a tested function checked on every request — exact-match and
> TLS-only, so a lookalike host or a userinfo authority fails it. It never asks for a full account
> number, and the ones that arrive anyway are cut to **four digits at the parser**, before anything
> can write them down; there is no column for more. Sign-in happens on a Plaid-hosted page in **a real
> browser, not a WebView** — a bank password should be typed somewhere this app demonstrably cannot
> see, and the address bar is how you check whose page it is. Your Plaid secret stays on this phone
> behind the keystore, which is a deliberate departure from Plaid's own advice and is why **the backup
> carries no credential at all**: restore onto new hardware and you reconnect.
>
> Two sign conventions are fixed once, in tested code, because both are the sort of bug that fills a
> screen with confident wrong numbers. A **balance is stored exactly as the institution reports it** —
> the number you can check against your bank's app — and the direction lives on the *kind*, so owing
> money makes you poorer rather than richer, and a card you overpaid counts as an asset rather than as
> a negative debt. A **transaction is negative when money leaves**, the way a statement reads, whether
> Plaid (which reports the opposite) or Mercury sent it.
>
> Bills arrive three ways and the app never pretends they are equally solid: **from your statement**
> (a card's real `next_payment_due_date`), **entered by you**, or **predicted** — three occurrences on
> a steady rhythm for a steady amount, and a prediction is dropped when a statement already covers the
> same obligation. A bill you type in **repeats**, because a one-off is useful for a tax estimate and
> useless for rent, and it steps from its last occurrence rather than from today — stop opening the
> app for three months and you come back to a rent bill for each of them, visibly unpaid, rather than
> one dated today that pretends the gap did not happen. It is also the one bill whose payee is matched
> loosely, since you write "Landlord" and the bank says `LANDLORD SEPT AUTOPAY`. Amounts are filtered *before* the rhythm is examined, so a gym charged $45 monthly
> that also takes $180 once a year keeps its monthly series instead of being thrown away as irregular.
> A card's bill leads with the **statement balance** and keeps the minimum beside it, because leading
> with the minimum is how a balance becomes permanent. There is **no "mark as paid" button**: a bill
> is settled when a matching payment turns up in the account, or when its LifeOps task is ticked —
> both facts, where a button would be an intention. Paid bills stay on the list, because a list that
> empties itself as the month goes on looks like the app forgot.
>
> The front screen leads with a **floor, not a forecast**: *"at worst $412 in six days, before
> anything else comes in."* Money coming in is deliberately **never projected** — payroll is regular
> and easily detected, and projecting it would draw a much prettier line that never shows a problem,
> on the strength of a promise about somebody's employer. So the line only ever falls, and the real
> balance on the trough day can only beat it. The burn rate comes from the last *complete* month,
> because a rate taken on the 3rd is three days divided by three days and projects a household into
> destitution by Friday.
>
> It will not **add unlike currencies**. The figures count accounts in whichever currency most of
> yours use, and the screen names anything left out — because converting would need a live exchange
> rate, which means a third host to talk to and a number on screen whose accuracy you didn't choose.
> It also **pairs the two halves of a transfer**: a debit matched by a credit of exactly the same size
> on another account within three days is money that never left the household, whatever either bank
> called it — which is what stops a $500 move to savings reading as $500 spent *and* $500 earned, and
> what stops the groceries you put on a card being counted again when you pay the card off.
>
> Tapping an account opens **its own page**: what is in it, what is due out of it, what has moved
> through it, and its **paperwork** — the statement, the payoff letter, the 1099. Those documents live
> on Repository's shelf rather than in a second store of Finance's own, findable from there without
> Repository knowing they were filed here.
>
> When a bank locks a connection, the row says so in the ordinary accent rather than in red — it is a
> thing to do, not a thing that broke — and **Sign in again** repairs the connection you already have
> through Plaid's update mode. Adding the bank a second time would mint a duplicate of every account,
> both counted in net worth, so the app does not make you.
>
> Bills go **on the LifeOps week**, the same seam Maintenance publishes upkeep on and for the same
> reason — one planner for the suite. A bill publishes itself ten days ahead as a task dated the day
> it falls due, carrying the figure in its title (a predicted one says "about", so the week never
> quotes a guess as though a biller had sent it), and **Finance ticks it off by itself when the
> payment lands**. Its own aspect, `Settings → Finance bills`, not Maintenance's — changing the oil
> and paying the mortgage are not the same part of anybody's life. Like Project and Maintenance it is
> not a peer on the sync spine, and it raises **no notifications of its own**: the reminder is a task,
> in the place you already look.
>
> A vehicle's **VIN opens three things**, and the app sends **eleven of its seventeen characters** to
> do it: the half that describes the model. The six dropped are the serial — the part on your title,
> the part a history service is keyed on — and they are dropped because they identify your vehicle
> *and* because NHTSA's decoder returns an identical answer without them, which was checked against
> the live API. The rule is a unit-tested function, not a habit. What comes back is **offered, never
> applied**: it fills in only the fields you left blank, and it **chooses a maintenance schedule**
> rather than fetching one — there is no public OEM API for service intervals, so a schedule is
> transcribed by hand from the manual and shipped as data (a Jeep Wrangler JL 3.6 Schedule A pack and
> a generic fallback), carrying its source and flagged provisional until somebody checks it. Applying
> one turns its items into ordinary plans you own; applying it again adds only what is new. The third
> thing is **safety recalls**, keyed by make/model/year with no VIN at all — NHTSA's *do not drive*
> and *do not park indoors* flags arrive as overdue, everything else as scheduled, because fourteen
> red lines on the day you add a used truck is a docket you stop reading. That answer is the only one
> here that **goes stale while the vehicle sits still** — campaigns open years after a car is built —
> so every vehicle schedule carries a standing six-monthly *check recalls*, and running the check is
> what ticks it off.
>
> Schedules understand **odometer milestones** as well as intervals — "spark plugs at 100,000 miles"
> is not "100,000 miles from now", which on a car bought at 60,000 is four years of being wrong — and
> milestones already behind you when a schedule is applied are taken as done, because nobody knows
> what the last owner did. A vehicle also gets a weekly **odometer prompt**, which is the one thing in
> the suite that completes a LifeOps task rather than reacting to one: a task can't carry a number, so
> typing the reading here ticks it off there. The recall check is the only other thing shaped like
> that, for the same reason — a task can't go and ask NHTSA anything either.
>
> A **house** gets the same treatment and **sends nothing anywhere to get it**, which is a finding
> rather than a gap: eleven characters of a VIN are a question about a *model*, and there is no half
> of an address that is anything but the household. So the reading happens on the device, out of
> fields somebody already filled in — and two of those are **pickers**, because the set of right
> answers is short and closed and "septic" spelled three ways is three answers to a question that has
> one. **Region** is the one with a lookup behind it — a table of ZIP prefixes pointing at eighteen
> regions, each carrying a **climate** (what the weather does on an ordinary Tuesday) and any
> **hazards** (what it does at its worst, which is a different list of jobs: Miami and Houston are
> both hot and humid, and only one of them is somewhere the shutters want finding before June). The
> table is coarse on a country this wide, so it is drawn as the guess it is — *"a guess from the ZIP
> code 96161"* — with a button that turns it into the answer, and nothing re-derives a region once
> somebody has picked one. **Type of home** decides what the *building* owes: a manufactured home is set on piers that
> settle, skirted rather than walled, with a roof that is coated rather than shingled — none of it on
> any site-built checklist — while a condo owner never owns the roof anybody would otherwise tell them
> twice a year to go and clear the gutters of. **What it has** is twelve tick-boxes and there is
> exactly one schedule per entry: tick solar and a solar schedule appears, tick gas and the flue and
> shut-off checks do. The **ZIP** in the address adds what winter does here, the year built adds the
> jobs peculiar to pre-1980 stock, and a loan against the asset means paperwork is owed as well as
> work — the annual escrow analysis, the statement checked against the balance this app computes, and
> the twice-yearly *can the mortgage insurance come off yet*, which a US lender must honour at 78% of
> the original value and will never ring to tell you about. Where a car gets one pack, a house is
> offered **five to eight of twenty-five**, and small packs rather than one composed list is what makes
> the ordinary case work: tick the septic tank six months late and its schedule simply appears as one
> more thing to apply, with everything you had already re-timed untouched.
>
> Nothing derived is stored, so nothing goes stale in a drawer. Its logic lives in
> `maintenance/logic/` under **206 JVM tests**. It holds `INTERNET` for those two keyless
> government lookups and nothing else — the mortgage, the address, the parcel number, the service
> history and the odometer have no code path to the network at all.

> **Secrets** (the suite's vault) is a peer module — see **[docs/SECRETS.md](docs/SECRETS.md)**.
> It is a **1Password-shaped password manager** for the household's own logins, cards, keys and
> notes — their **second factors**, their **passkeys**, and the **password each one had before this
> one** — and, under the same lock, **every credential the rest of the suite holds**: Finance's Plaid
> keys and bank access tokens, Citation's catalogue sign-ins and library card, and the Operations
> Sandbox's own GitHub update token and Azure backup signature. That second half is why it
> exists. Every app here keeps its credentials in `EncryptedSharedPreferences` behind an Android
> Keystore key and deliberately leaves them out of the backup, which is right — a zip in a cloud
> drive carrying a bank access token would be the worst thing this suite could produce — and which
> cost one thing every contributor states plainly: **restore onto a new phone and every credential is
> gone**, because a key held in one phone's hardware cannot be carried to the next one.
>
> That included the **shell itself**, which is the case that shows the pattern is not an app-by-app
> courtesy: the container holds the token its updater checks releases with, kept the same way for the
> same good reason, and lost on the same restore — after which the suite quietly stopped being able
> to say a new version existed. So a secret's owner is a `SecretOwner`, either a hosted app or the
> container, rather than an `AppId`; the shell files at `sandbox/self/github-token`, mirrors, reads
> through and refills like any app, and appears in the household's own vault list under its own name.
>
> And the reading is not only lazy. A read-through puts a credential back when something asks for
> one — which on the first morning after a restore is a background sync at 2am asking a vault that
> is still shut, and reporting a connection that looks as though it was never set up. So **unlocking
> the vault hands every app back what it is missing**, in one round, before anything asks: the
> queued writes go in first, then each app takes back the refs its own rows say it should have.
> One passphrase, and the suite has its credentials again without a single app being opened.
>
> The vault changes what the key *is*. One file, sealed with **AES-256-GCM** under a key derived from
> a **master passphrase** (PBKDF2-HMAC-SHA256, 310,000 rounds) that wraps a random vault key — a
> passphrase in somebody's head rather than anything the phone or the archive holds. So the vault
> **travels in the backup**, on purpose, holding credentials: copy it out of the zip and you have
> what a thief holding the phone would have, which is a ciphertext and no key. Each app still keeps
> its own store as the working copy and now **mirrors** every write into the vault and **reads
> through** to it when its own store comes up empty — which after a restore is every credential at
> once, and then never again. Nine reconnections become one passphrase, and a phone with no vault
> behaves exactly as it did before.
>
> A write that arrives while the vault is shut is queued in memory and filed on the next unlock,
> which was correct and used to be invisible — and the queue does not survive the process, so a
> household that never happened to open Secrets that day lost the mirror silently. The vault is now
> **watchable**: the sandbox's home screen puts one line under the clock — *"3 credentials are
> waiting for your vault — tap to unlock"* — and a count on the Secrets tile. A locked vault on its
> own is deliberately **not** news: it comes up shut on every process start, so saying so would be a
> permanent badge nobody reads. The line appears only when a credential is actually stranded, and on
> a phone with no vault it offers to make one rather than to unlock one, because the queue lands the
> moment a vault exists.
>
> It holds **passkeys**, too, and they are why the whole suite now asks for **Android 14 or newer**:
> a third-party app can hold one only through Credential Manager's provider API, which does not
> exist below it. Shipping that as a gated feature — present on some phones, apologised for on
> others — was the worse of the two options for a credential nobody can recover, so the floor moved
> instead. A passkey is a key
> pair, and where the private half lives decides what happens the day the phone does not come back:
> a platform passkey sits in hardware-backed storage, which is the same binding this module exists
> to work around. Kept here it rides the vault into the backup, so it **survives a new phone** — and
> the authenticator says so honestly, setting the backup-eligible and backed-up flags a site reads to
> decide whether to keep a password fallback. The WebAuthn half is pure JVM in `:vaultkit` — CBOR,
> the COSE key, the authenticator data, the signature — and its tests build a registration, sign
> against it, and **verify with the public key the site was given**, which is the check a relying
> party's server runs. ES256 only, attestation `none`, no AAGUID claimed, and the signature counter
> deliberately fixed at zero, because a credential that legitimately lives on two phones would
> otherwise report itself cloned on every restore.
>
> It also **fills passwords in other apps**, which is the one thing here that runs when the app is
> not on screen and the one that talks to software the household did not choose — so it is the one
> with the rules written down. An `AutofillService` must be bound by the system, so it is exported
> behind `BIND_AUTOFILL_SERVICE`: a permission the platform holds and nothing installable does,
> inert until somebody picks Secrets in system settings. The matching is
> in the pure-JVM `AutofillMatch` and is mostly a list of refusals — a **managed credential is never
> offered to anything**, a match has to be **earned** by the item's own address, a subdomain matches
> its parent while `bank.com.evil.example` does not (the suffix test is on label boundaries), and a
> form nothing is filed under gets **no rows at all**, only a door into the vault's own list where
> the person picks for themselves. A locked vault stays locked and offers a way in rather than an
> answer, and it will not fill this suite's own package: a vault that fills its own passphrase box
> is a vault with its key inside it.
>
> Getting into it is the other half of leaving somewhere else, so it **imports**, by three routes in
> order of preference. First **Credential Exchange** — the FIDO CXP/CXF transfer Android now defines
> — where the household picks another credential manager from a system selector, unlocks *that* app,
> and it hands its contents straight across: **passkeys included**, which no exported file can
> carry, with no plaintext written anywhere. Imported passkeys arrive able to sign; CXF ships the
> private key without the public half, so the public key is recomputed from it in thirty lines of
> `BigInteger` curve arithmetic, and the tests both recompute twenty real key pairs' public halves
> and then sign with an imported credential and verify against the computed key — the check a
> relying party runs. Second, for the managers that have not implemented the transfer, a **share
> sheet** entry: Chrome → Export passwords → screen lock → share to Secrets → the review list, and
> the plaintext file is never saved to disk at all. That share target is the module's third exported
> component and the first one another app can reach, which the manifest argues rather than absorbs —
> it returns no result and reveals nothing, the vault still has to be opened on the spot, nothing is
> written without a review, and it closes when the vault does. Third, a file picker for an export
> made on a computer, reading a browser's CSV (Chrome, Edge, Brave, Firefox, Safari, Apple
> Passwords) or 1Password's `.1pux`, which brings the cards, notes, wifi keys, custom fields, extra
> addresses and password history a CSV drops. Where a transfer is impossible the import **names the
> menu item in each manager** rather than pretending to be a connection: no other API enumerates a
> manager's vault, 1Password's server holds ciphertext it cannot open, and this module has no
> network to ask with in any case. Nothing is written until the household has seen a list, and
> whatever is taken keeps the item itself — its tags, its fields, its second factor — changing only
> what the import actually supplies.
>
> Which leaves the hard question of any import: when one account is already here with a *different*
> password, which of the two is current? That is the day somebody has both Google's copy and
> 1Password's and they have drifted apart, and it arrives four hundred rows at a time. So the plan
> **resolves it from evidence** and shows its reasoning per row: a password already in this item's
> history was replaced here and cannot be the newer one; an import whose own history holds what this
> vault currently has is the later copy; failing those, the **modified timestamps** decide, which a
> Credential Exchange transfer, a 1Password `.1pux` and a Firefox export all carry. An older copy is
> neither discarded nor allowed to overwrite: it is filed as a **previous password**, which is what
> a password history is for, because the account you get locked out of is the one whose password
> changed on one device and not the other — and the item's own `updatedAt` is deliberately left
> alone, since the password did not change today and **Check** reads that field to decide what is
> stale. Only the genuinely undated collisions are left unticked, and an undated export never gets
> to claim it is the newest copy: the readers leave the timestamp unset rather than stamping today,
> which is the difference between an import and a silent overwrite of everything changed since.
>
> And because an import is also what fills a list past reading, the vault **groups by site**:
> everything under one address is one row saying how many sign-ins are behind it, shut by default,
> opening in place. Not folders — it is computed from each item's own address through the same
> matcher autofill uses, so there is nothing to file and nothing to go stale; a site with one login
> is never a folder of one, a group sorts where its best member would have so a favourite keeps its
> place, an item with no address is left exactly where it was, and a search flattens the whole thing
> because a match must never hide inside a shut group.
>
> Second factors arrive as seeds that make codes rather than as text, and the wrong file gets a
> sentence about what to pick instead — including this app's own sealed vault, which is sent to the
> screen that can actually open it. What it cannot do is delete the export afterwards: that file is
> every password the household has in plain text, and the screen says so twice rather than reaching
> into shared storage.
>
> It is the one app whose restore refuses to restore: an archived vault is not swapped over a live
> one — that would delete every password added since the backup, with nowhere to fetch them from — it
> is staged and **merged** item by item, newest wins, tombstones respected, with a report of what
> changed. There is no `INTERNET` permission in the module and no HTTP client on its classpath: no
> sync, no account, and no breach lookup, not even the k-anonymous kind — which is a restriction
> rather than a shortfall, and the **second factor** is the proof: a TOTP code is HMAC over the clock,
> so it works here exactly as well as it would anywhere, and it replaces a separate authenticator app
> whose seeds died with the phone. Seeds are **scanned** from the site's QR code or typed from the
> key beside it; the scanner is this module's one use of the camera, opens on a tap, keeps no image,
> decodes in-process, and runs behind `FLAG_SECURE` because what is in front of the lens is a picture
> of a seed. An item also keeps **the ten passwords it
> used to have**, because the commonest way to lose an account is not forgetting a password but
> changing one — a form that said it saved and stored something else, or a tablet still signed in on
> the old one. The mirrored credentials get no such history: a rotated access token opens nothing, so
> keeping it would be storing plaintext with no use for it. It cannot recover a
> forgotten passphrase — nothing can, which is the point — but the unlock screen offers to **delete
> the vault and refill it**: the managed credentials were never the vault's only copy, so each app
> files what it still holds and the household is told exactly what came back and what did not. The format, the crypto, the generator,
> the audit, the merge, the import readers and the one-time-password generator are the pure-JVM
> `:vaultkit` under **274 JVM tests**, most of which assert that something *fails* — the vault refusing a wrong
> passphrase, a flipped bit, a header edited to claim a cheaper KDF, a spliced key, a truncated file;
> the search box refusing to match a password or a seed somebody typed into it; autofill refusing a
> lookalike domain, an app with no address filed against it, and a mirrored bank token; a replayed
> passkey signature failing to verify over client data it was not made for. The codes themselves
> are checked against **RFC 6238's own test vectors** on all three hashes, which is the only test
> worth having for a generator whose failure mode is six plausible digits that no site accepts.

> **Utilities** (the takeovers) is a peer module — see **[docs/UTILITIES.md](docs/UTILITIES.md)**.
> Every other app here replaces a *service*; this one replaces pieces of the **phone**, and the reason
> is the same each time: the stock component is fine, and it reports to somebody else. A keyboard
> sees every password, message and search typed on the phone. A messaging app sees every
> conversation. So Utilities is a **shelf of takeovers**: one row per part of the phone that leaks,
> each showing whether it is on, half-done or off, and each with the one thing to do next. Two ship.
>
> The **keyboard** is a real input method — QWERTY with printed long-press alternates, a symbols page
> you are not thrown out of after one character, a numeric pad for numeric fields, double-tap caps
> lock, and sentence capitalisation — and the whole argument for it is a dependency it does not have:
> **no `INTERNET` permission, and no HTTP client on the module's classpath.** What it remembers is a
> word list with counts, capped at a few thousand, kept in a plain text file you can read, **listed
> word by word on its own settings screen** with a Forget button on every row. Nothing is learned from
> a password field, a field marked `noSuggestions`, or a browser's private window — those editors say
> so and the keyboard listens. Turning learning off empties the list as well as stopping it growing.
>
> **Messages** has *two rungs*, which is the part worth knowing before you switch anything on.
> **Reading** needs one permission and changes nothing else about the phone: the threads are already
> in Android's own store, Utilities draws them, and your carrier's app keeps delivering and notifying.
> That rung is worth having on its own — it is the one that answers "I want my own window" — and
> because nothing moved, it costs nothing. **Default** is the whole job: texts *and picture messages*
> arrive here, this app stores them and notifies. Switching the role back in system settings undoes it
> immediately — nothing was moved or deleted, which is the property that makes every takeover here
> reversible, and it is what the confirmation leads with.
>
> **Picture messages are handled end to end, and that is a subsystem rather than a field.** An MMS is
> not a bigger SMS: what arrives over the radio is a WAP push announcing that something is waiting at
> a URL on the carrier's own MMSC. `SmsManager` does the network half — it alone knows the MMSC
> address and the APN — and hands back *bytes*, which nothing in Android will parse for an app. So
> `messages/pdu/` is a **pure-JVM WAP codec** (WAP-230 primitives, WAP-209 encapsulation) under unit
> test including a full encode-then-decode round trip, and `messages/mms/` is the thin Android layer
> that moves its results in and out of the platform's own store. A placeholder row is written
> *before* anything is fetched, so a failed download leaves a card with a Fetch button rather than
> nothing; declining to auto-download sends the network a **deferred** response rather than silence,
> because a network that hears nothing re-announces and the household sees the same photograph arrive
> four times. Auto-download is on, and **off while roaming by default** — an automatic download abroad
> is a charge nobody sees coming. Every picture sent is re-encoded to fit the carrier's cap (scale
> first, then quality; an animated GIF that fits passes through untouched and one that does not is
> refused rather than silently flattened), because a message over the cap is accepted by the radio and
> dropped by the network with no error anywhere. The `content://` URI the platform insists on is
> served by a provider that is **not exported** — the usual implementation exports it and makes every
> picture on the phone readable by any installed app — and granted to the two system packages that do
> the work for one transfer, then revoked. None of it is a network permission: this module still
> declares no `INTERNET` and has no HTTP client.
>
> Both surfaces are set with the **same appearance sheet, borrowed in design from Citation's reader**:
> four presets and a custom one, a page colour and a text colour you pick yourself, a **warmth slider
> that cuts blue out of each colour rather than laying an orange sheet over them** (so the surface
> warms without losing contrast), a face — including one loaded from your own font file, since the
> ones people ask for are not ours to ship — a text scale, a roundness and an air setting. One button
> on each screen takes the other's colours. An accent that cannot be read on the surface you chose is
> moved toward the text colour until it can be, so a send button can never disappear; a received
> bubble is derived from the surface and stepped up again when the first step vanishes into it.
>
> **Messages between two installs of Utilities are encrypted end to end, by default, with no setup.**
> The obvious way to get that on Android is RCS, and a third-party app *cannot*: there is no API —
> `RcsMessageStore` was removed from AOSP before it shipped, what remains is `@SystemApi` behind
> carrier privilege, and the default-SMS role grants SMS and MMS and nothing else. Google Messages
> does RCS because it ships Google's own client stack. So Utilities encrypts over the transport it
> actually has: **X3DH and a Double Ratchet** — the Signal design — implemented in `messages/seal/`
> as pure JVM, pinned against **RFC 7748** and **RFC 5869**'s own vectors, with the curve written out
> rather than called so that the code CI exercises is the code the phone runs. Forward secrecy, so a
> key recovered today says nothing about yesterday; post-compromise recovery, so a stolen state stops
> working after one round trip; skipped keys kept and bounded, because SMS reorders and duplicates
> and a ratchet that refused anything out of order would lose messages every other app shows.
>
> Two phones find each other over a **data SMS on a port** — never stored, never displayed, so
> somebody without the app sees *nothing* rather than a line of base64 from a friend. That is what
> makes automatic setup acceptable, and it still costs one message, so it is a setting that says so.
> Trust is **on first use and verifiable afterwards**: a strip above each conversation says *not
> encrypted*, *encrypted — not verified*, or *verified*, and tapping it shows a 60-digit safety
> number with a QR code. Nothing nags anybody to check it. An identity key that changes is **refused,
> not adopted** — it means a reinstall or somebody in the middle, nothing can tell those apart, and
> quietly re-pinning would make the distinction unobservable.
>
> The scope is stated exactly, in the app and in the code: what is protected is the message **in
> transit**. A received message is decrypted and stored in Android's own database like every other
> message, because this app owns no data — which is the property that makes the takeover reversible.
> The carrier still knows who, when and how long. And it is Utilities to Utilities; it is not Signal.
> The identity key survives a restore through the **vault**; the ratchet sessions are the one thing
> in the suite deliberately excluded from the archive *because restoring them would be unsafe*.

> Utilities **owns no data**, and that is the design rather than a gap: the texts and the pictures stay
> in the platform's provider where they have always been. Its backup slice is the appearance file, the
> word list and any font you supplied — a few kilobytes. Its logic is pure JVM and unit-tested: the key
> layouts and the shift/layer machine, the word list's refusals, the colour maths, the WAP codec both
> ways, the MMS size budget, the rule for when a sent message has landed in the store, which protocol
> a message should become, the whole of the sealed-messaging protocol, and what each takeover's state
> adds up to.

> **Repository** (the suite's shelf) is a peer module — see **[docs/REPOSITORY.md](docs/REPOSITORY.md)**.
> Every app here eventually hits the same wall: a thing it tracks has a piece of paper attached to it.
> Solved once per app that becomes five stores, five backups, and a household that has to remember
> where it filed something. So there is one shelf with **two doors onto the same documents** — its own
> screen, where the mortgage statement is findable without opening Maintenance, and a section it
> **lends** to the app that owns the thing, so the furnace's manual sits on the furnace. The two doors
> open onto each other: the shelf can be **opened at a place** rather than merely started — one
> drawer, one asset's documents, one document — and the section on the asset carries the button that
> does it. A destination is a filter on the one list and never a screen, so the deepest link still
> lands you on the shelf with everything else one press away, and an address naming a document
> deleted since opens the whole list rather than an empty screen insisting nothing is filed.
>
> A document knows what it is about by **carrying a label, not a foreign key**: Maintenance says "this
> is about `a3f2`, which is called *2018 Jeep Wrangler*", and Repository understands none of it — the
> dependency arrow points into this module and never out. That is what lets a search for "wrangler"
> find the truck's manual in a module that has no idea what a Wrangler is.
>
> **It stores documents and does not read them.** No OCR, no extraction, no interpretation — which is
> what makes it safe to keep a mortgage statement and a lab result in one drawer. It declares **no
> permissions at all**. Pictures are downsampled; a PDF is copied byte for byte, because a re-encoded
> PDF is not the file the bank sent. Documents leave by exactly one road — a copy made into
> `cacheDir/exports` when somebody presses Open or Send — and the backup carries the **files as well as
> the rows**, restoring them first, because here the rows are only captions.
>
> An app that already keeps its own paperwork **lends it read-only** rather than migrating: Health's
> documents appear on the shelf beside everything else, while every change to one still happens in
> Health, which is where the rules about deleting them live.
>
> The shelf is also **one of the places Android offers**, beside Drive and Downloads: a
> `DocumentsProvider` puts the same drawers, names and search into the system Files app and into every
> other app's Open dialog, so the mortgage statement is attached to an email without first being found
> in Downloads under `Scan_20240412.pdf`. Read-only — no create, delete, write or rename, the same
> line the routes sit on — and guarded by `MANAGE_DOCUMENTS`, so no app can bind to it and go looking;
> what an app gets is the one document the person picked. Its document ids *are* the app's own deep
> links, so the id the Files app remembers is the string that opens the shelf at that document.
>
> The shelf also **answers in a sentence**: `/v1/Repository/local/…` is the suite's third dispatcher
> (after LifeOps and Project), so "where is the Wrangler's warranty" is a route rather than a scroll.
> The line it sits on is drawn tighter than anywhere else in the suite — the routes read and they
> correct captions; they cannot put a document on the shelf, take one off it, or hand one out, and
> each refused address is asserted in a test rather than merely left unwritten. The address machinery
> that makes this possible moved out of LifeOps into `:connectkit` to get here, because the arrow into
> this module still points one way.
>
> Documents also come **off a drive and go back onto one, targeted**: pick Google Drive, OneDrive or
> Dropbox, choose the four files you actually want, review the list — untick the two dead drafts,
> rename the third — and file the lot in one press; send one back, or everything a search has
> narrowed to, into a folder chosen once and remembered per drive — and the shelf goes with them: an
> export writes one small manifest beside the files, so on the second phone each document arrives with
> its real name, its kind, its note and what it is about, and anything already there is left alone
> rather than filed twice. That is not sync and does not become it — nothing watches the folder and no
> credential exists to watch it with; a copy taken at a moment simply carries what the household typed
> about it. There is **no Drive API, no
> OneDrive API and no credential**: each of those drives already publishes itself to Android as a
> document provider, so this is the system picker with a starting point, and the module still holds
> no permissions. What lands is a copy taken at a moment, never a link that syncs — see
> `repository/logic/Drives.kt` for the argument. Something on the shelf is **attached** to a project
> or an asset afterwards without being copied again, which is how one document stops becoming three.

> **Logistics** (the pantry/inventory app) is a peer module — see **[docs/LOGISTICS.md](docs/LOGISTICS.md)**.
> It fills a virtual pantry from a Walmart order (PDF or pasted text), draws it down as you log the
> meals you cooked ("for *X* meal, here's what I used"), **builds a grocery list** from what's running
> low or a recipe's missing ingredients — and shelves it back into the pantry when you've shopped —
> and grabs recipes from links **or from screenshots of one** (on-device OCR; the picture is kept with
> the recipe), reusing LifeOps' food & recipe catalog rather than keeping its own. It also carries
> **the whole food-and-calorie side of LifeOps** — the day's diary, its planned-vs-confirmed totals,
> Confirm/Adjust, ad-hoc entries, custom foods and planning a recipe onto a day — as its own **Food**
> tab, writing through LifeOps' food service into LifeOps' diary rather than keeping a second one, so
> a bowl of chili logged in either app is one row. Cooking a known recipe can put its calories in the
> diary in the same tap that deducts it from the shelf. Its framework-free parsers live in
> `logistics/logic/` and are JVM-unit-tested.

---

## What it does

| Screen | Purpose |
|---|---|
| **This Week** | The cockpit. Create tasks, set priority/estimate/due date, log time (timer, Pomodoro, or manual), complete/skip/carry tasks, then **close the week**. |
| **Game** | An arcade wave-holdout run funded by the resources your real work earns. Spend Energy to enter, commit banked resources into your loadout, and fight a week-seeded run. See **[DESIGN.md](DESIGN.md)**. |
| **Resources** | RPG-style resource slots that fill as you complete work, mapped from your aspects. The economy behind the Game. |
| **Reports** | Trends: completion rate, aspect balance, time spent, scoring, operation health, priority breakdown, resource usage, carryover. |
| **Growth Record** | A permanent, per-week concentric-ring history. One ring per week; effort shows as colour; skipped weeks leave grey scars. |
| **Settings** | Aspects & categories, operations, cost resources, notifications, theme, and all backup/export actions. |

### Core concepts

- **Aspect** — a top-level area of life (e.g. *Body*, *Craft*, *Mind*). Has a name and colour.
- **Category** — a sub-grouping inside an aspect.
- **Operation** — a longer effort that groups related tasks within an aspect. (Called a
  *Project* until the suite grew a Project app of its own; the word now belongs to that app.)
- **Week** — Monday→Sunday. The current week is open; you **close** it manually, which
  snapshots it, carries forward what you chose to keep, and starts the next one.
- **Task** — a unit of work with a priority, optional estimate, due date, and logged time.
- **Commitment** — the handful of a week's tasks whose completion decides whether the week
  worked. Marked with a star, worth no extra points, and the one thing that lets the app say
  *"rest is earned"* rather than quote a percentage.
- **Resource value & scoring** — completing a task earns `resourceValue × accuracy`, where
  accuracy rewards estimating well (see the guide).

---

## The Growth Record (rings) — design note

Each closed week is drawn as **one concentric ring**, fixed thickness, accumulating
outward from a seed. Bands within a ring are the aspects you spent hours on; colour and
glow encode effort; a zero-hour week is a permanent grey **scar**.

**The one invariant:** *a ring, once drawn, never changes.* Adding week N+1 must not move,
resize, recolour, or restack any earlier ring. Everything is subordinate to that — thickness
is flat (the view zooms, the geometry never does), a ring's radius depends only on the weeks
before it, and colour/track map to a **stable aspect id**, never an array index. The aspect
list is append-only.

To keep history faithful even when an aspect is later **deleted, renamed, or recoloured**,
week-close seals a per-aspect `{minutes, name, colour}` blob into the week snapshot
(`aspectHistory`). Sealed history is immutable; the open week (and any pre-feature week) falls
back to live time-entry derivation. This matters because `tasks.aspectId` is `ON DELETE SET
NULL`, so a live join alone could not survive a deletion.

The render layer emits a **shape-agnostic primitive list** (crisp `fill` + `glow` bands), which
feeds both the on-screen Compose canvas and the **SVG** exporter — so vector export stays a
first-class feature.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Room** (SQLite) for persistence, **Gson** for backup/import
- **MVVM**: `Screen` (Compose) → `ViewModel` (`StateFlow`) → `Repository` → `Dao`
- **WorkManager** for reminders, **Glance** for the home-screen widget
- Min SDK 26 · Target/Compile SDK 35 · JDK 11

### Project layout

```
app/src/main/java/com/lifeops/app/
├── data/
│   ├── db/            Room database, entities, DAOs, migrations
│   ├── model/         Plain domain models (Models.kt, Weather.kt)
│   ├── repository/    Repositories (the only thing ViewModels talk to)
│   └── weather/       NWS (api.weather.gov) network client — the only thing that touches the net
├── ui/
│   ├── screens/       thisweek · resources · reports · growth · settings · operationdetail
│   ├── components/    Reusable composables (header, dialogs, task rows, …)
│   └── theme/         LifeOpsTheme — a one-line wrapper over the suite's theme (:suiteui)
├── util/              Pure logic: scoring, dates, growth rings/colour/export, CSV, weather
├── widget/            Glance app widget
└── worker/            WorkManager workers (reminders)
```

The **growth** logic lives in pure, Android-free modules so it is unit-testable on the JVM:
`util/GrowthRings.kt` (geometry/scene), `util/GrowthColor.kt` (hex/HSL + intensity),
`util/GrowthData.kt` (assemble live + sealed snapshots), `util/GrowthExport.kt` (CSV + SVG),
`util/Csv.kt` (shared CSV quoting).

---

## Weather foundation (Phase 1) — design note

LifeOps grows a **source-agnostic weather layer**. The rest of the app asks *"what are current
conditions?"* against plain models in `data/model/Weather.kt` (`WeatherReport`,
`CurrentConditions`, `ForecastPeriod`, `WeatherAlert`) and never learns where the numbers came
from. Today they come from the US National Weather Service (`api.weather.gov` — free, keyless,
US-only), but that lives entirely behind `WeatherRepository`.

**Cache-first, so opening LifeOps never waits on weather.** Reads stream straight from a local
Room cache (`weather_locations`, `weather_snapshots`, `weather_alerts`, migration 28→29); the
network is touched only by an explicit `WeatherRepository.refresh(...)`, which folds any failure
into a `Result` so a dropped connection just keeps the last cached report on screen. Each refresh
stores the current conditions as flat columns plus the full hourly/daily forecast as JSON blobs,
so an entire `WeatherReport` rebuilds from cache with zero network. The weather cache is
intentionally **left out of backup/restore** — it's regenerable.

The Android-free, JVM-testable pieces sit in `util/`: `WeatherMath.kt` (NWS heat-index /
wind-chill "feels like") and `NwsParser.kt` (raw api.weather.gov JSON → models). Only
`data/weather/NwsClient.kt` performs I/O (via `HttpURLConnection` — no new dependencies), which is
why the app gains the `INTERNET` permission for the first time.

**Phase 2 — awareness.** `worker/WeatherRefreshWorker.kt` keeps the cache warm in the background:
a ~2-hour periodic WorkManager job (network-constrained, `KEEP` so relaunches don't reset it,
scheduled from `LifeOpsApp`) that refreshes every location, prunes expired alerts, and — while any
alert is active — chains a shorter one-time follow-up for a denser cadence. `util/OutdoorScore.kt`
is the rules-based scorer: a pure 0–100 *discomfort* total (0–25 Excellent … 76+ Avoid) built from
feels-like, humidity, UV, wind, rain probability and storm risk, returning human-readable
positives/warnings.

**Phase 3 — LifeOps integration.** Tasks gain optional weather constraints via a
`task_weather_requirements` 1:1 side table (outdoor-preferred, duration, max/min temp, avoid-rain,
max wind) — kept off the core `tasks` schema so the create/edit pipeline is untouched.
`util/BestTime.kt` is the pure recommendation engine: it scores each forecast window with
OutdoorScore, disqualifies windows that break the task's hard limits or a household member's
comfort ceilings (and optional calendar busy-labels), and ranks the rest best-first with a friendly
match-%. `util/WeatherCards.kt` assembles the dynamic cards (severe-weather **Warning** → **Morning**
conditions → per-task **recommendation**). It all surfaces on a self-contained **Weather** screen
(Planning hub → Weather): add a location, see current conditions + outdoor rating + alert cards, set
per-task weather needs, and get best-time suggestions for the week's outdoor tasks. Requirements are
included in backup/restore (v9).

**Phase 4 — saved activities.** The **Activities** screen (Planning hub → Activities) is a library
of reusable weather profiles (`activity_templates`, migration 31→32). Eight built-ins (Mowing,
Gardening, Car Washing, …) are seeded once on first launch, but every template — built-in or not —
is a fully editable/deletable row, and users can **build their own from scratch**. Applying one from
the Weather screen's requirement editor stamps its defaults onto the task's weather requirement.
Templates are in backup/restore (v10). The other half of Phase 4 — household profiles — already
shipped as the People feature, which the best-time engine consumes.

**Phase 5 — advanced.** `util/SevereWeatherIntel.kt` turns alerts + the hourly forecast into
actionable advisories ("Storm approaching · clearing by 4 PM", with a *Delay ~90 min* hint),
surfaced as a new `WeatherCard.Advisory`. Radar is deliberately on-demand only (the roadmap's "avoid
a constant feature"): a **View radar** action resolves the nearest NWS station (`/points`
`radarStation`) and opens the official radar in the browser — no image library, network only when
asked. `util/PreferenceLearning.kt` closes the loop: when you seed a task from an activity then
change a limit, the delta is logged (`activity_overrides`, migration 32→33); once a field trends the
same way enough times, the **Activities** screen suggests updating that activity's default ("You keep
mowing above the recommended temperature — raise the threshold?") with one-tap Apply/Dismiss. All in
backup/restore (v11).

**Phase 6 — where you actually are.** A location stops being a latitude you type in.
`data/weather/DeviceLocationProvider.kt` asks the device where it is — platform `LocationManager`
only (no Play Services), `ACCESS_COARSE_LOCATION` only (a forecast cell is ~2.5 km square, so fine
location would buy nothing), cheapest-first: a recent last-known fix, else one bounded active
request, else the stale fix rather than nothing. `WeatherRepository.setDeviceLocation(...)` keeps
**one reserved row** pointed there instead of accumulating a location per fix; it sorts ahead of
hand-added places, making it the weather screen's default and the "primary" that stamps counter
ticks. A fix within 2 km of the stored one is treated as no movement and changes nothing, so a phone
on a table can't discard a good forecast; past that the row moves and its now-wrong snapshots and
alerts are dropped. Every failure is a value (`Fix.PermissionMissing` / `LocationDisabled` /
`Unavailable`), never an exception, so each one can be phrased for the user. This is what the
**Operations Sandbox home screen's weather tile** is built on (see
[docs/OPERATIONS_SANDBOX.md](docs/OPERATIONS_SANDBOX.md)): current conditions, today's high/low, and
an alert strip, read from the same cache LifeOps uses — cache-first, refreshed only past 45 minutes,
tapping through to the full Weather screen. `util/TodayOutlook.kt` is the pure piece that turns NWS's
alternating day/night halves into "the rest of today"; `util/Geo.kt` is the pure distance check
behind the move threshold.

**Phase 7 — the two taps.** Both summary cards on the Weather screen are the top of something
deeper, and both are now pressable.

*Current conditions → radar.* A **map screen** pinned to the location the reading came from — which,
for the device row, is literally where you are. The map is assembled from raster tiles rather than
delegated to a maps SDK: no API key, no new dependency, and the same `HttpURLConnection` the
forecast already travels over. `util/TileMath.kt` is the Web Mercator "slippy map" projection
(lat/lon ↔ tile, EPSG:3857 tile bounds for a WMS `GetMap`, metres-per-pixel); `util/MapCamera.kt`
holds the camera and turns it into the list of tiles a viewport needs, plus the scale bar. Both are
pure and unit-tested, so pan, pinch and pin placement are JVM questions. `data/weather/MapTiles.kt`
declares the layers — an OSM street base, and radar from either NOAA's own GeoServer (CONUS base
reflectivity, WMS) or the Iowa State NEXRAD mosaic (XYZ), switchable from the top bar because a
radar screen showing nothing is worse than one showing a second opinion. `data/weather/TileLoader.kt`
is the only new thing that touches the network: an in-memory, byte-sized LRU that lives exactly as
long as the screen, returns `null` rather than throwing (a tile that doesn't load is a square of
empty map, not an error dialog), and writes nothing to disk — radar is a *now* picture, and a cached
one would only ever be wrong. Zoom past what a radar product publishes and its deepest tiles are
drawn larger rather than requesting a level that 404s. The old browser hand-off survives as **Open
NWS radar site** in the layer menu.

*Today's conditions → the full forecast.* A **detailed weather screen**: the next 24 hours as an
hourly strip drawn against its own temperature range, the week as one row per day (NWS's day and
night halves folded together, tap for the official prose), every reading the feed carries as a tile,
the full text of any active alert *including its instruction*, the outdoor score broken into the
reasons it came out that way, and the best daylight windows on weather alone. `util/WeatherDetail.kt`
is the pure builder behind all of it — which hours count as "ahead", how the halves fold, when rain
is worth a sentence — so the screen only renders. Everything it shows is already in the cache the
card was built from, so the tap costs nothing and works offline; refresh is the only thing on either
screen that reaches the network, and only when asked.

---

## People (Planning) — design note

The **People** page (Planning hub → People) holds household profiles: a name, weather-comfort
preferences (max/min feels-like, UV / wind / rain ceilings, sun sensitivity), a freeform activity
note, and a timeline of notes. Every preference is nullable — "no opinion" never rules a time slot
out. These are the **Phase 4 household profiles** the weather roadmap's outdoor scoring consumes.

Tasks are marked as *involving* people through a `task_people` many-to-many join (both sides
cascade). Involvement is managed from a person's detail screen, which lists the tasks that involve
them and attaches more from the current week — so the task-creation flow stays untouched. Tables
(`persons`, `person_notes`, `task_people`, migration 29→30) are additive and, unlike the weather
cache, **included in backup/restore** (backup v8) since profiles are real user data.

---

## Milestones (History) — design note

The **Milestones** page (History hub → Milestones) records the rare, once-in-a-lifetime
accomplishments that don't fit the weekly task rhythm. A milestone is a title, an optional
description, a point value, an *achieved on* date, and an optional attachment to an **aspect**
and/or a **person** (nullable FKs with `ON DELETE SET NULL`, the same shape as `tasks.operationId`,
so removing an aspect or person leaves the milestone standing with its link cleared).

Milestones are the **deliberate exception to "only closed weeks emit resources."** Because they are
logged after the fact for something already accomplished, their points are **granted immediately**
rather than at week-close: on creation, an aspect-attached milestone mints its points into that
aspect's mapped game resources through the *exact same path* week-close uses
(`GameResourceMappingDao.getByAspect` scaled by each mapping's weight → `GameResourceDao.addValue`),
and records a `milestone` **resource transaction** so the grant is visible in the Resources ledger.
A milestone with no aspect (or an aspect with no resource mappings) simply keeps its point value as
part of the record. Like every mint in LifeOps the grant is **permanent** — deleting a milestone
removes the record but never claws back already-granted points. The `milestones` table (migration
44→45) is additive and **included in backup/restore** (backup v15); restore upserts the rows
without re-running the grant, so restoring never double-mints.

---

## Week-close review — design note

Closing the week is LifeOps' one **mint** — the moment work becomes resources and a ring is sealed —
so the close dialog is a **review**, not a rubber stamp. Before you confirm, it holds the week you're
about to close against the trailing sealed history and shows: headline metrics with honest deltas
(completion vs last week in percentage points, time logged vs the trailing average, hard-deadline hit
rate), an **aspect-balance** read that surfaces *grey scars* (aspects gone two-plus weeks with zero
minutes — the same neglect the Growth Record marks), and a short set of **earned observations** —
the sardonic honest-mirror lines the app promises, each fired only when the numbers justify it
("Body has been a grey scar 3 weeks running", "You keep underestimating — most tasks ran over").
Pick a self-rating and, if it disagrees with the board, the mirror says so ("You rated this an 8; the
board says 40% done").

The whole retrospective is pure, Android-free logic in `util/WeekReview.kt` (`WeekReviewBuilder` +
`ClosingWeekStats` → `WeekReview`), unit-tested on the JVM like `GrowthRings` / `BestTime` /
`ScoringUtils`. The estimate window matches `ScoringUtils` (±15 min), and historical time comes from
each snapshot's sealed `aspectHistory`, so the review reads the same faithful record the rings do. It
is **read-only** — nothing new is persisted; the existing self-rating/note still seal into the
snapshot, and the close path (mint next week, snapshot/settle, seed recurring) is unchanged.

---

## The week's bar — design note

LifeOps' completion rate answers *how much of the list moved*. It cannot answer *am I done* —
a percentage has no idea which of the tasks mattered — and "am I done" is the question the whole
week-shaped rhythm exists to answer. So a task can be marked as one of the week's **commitments**
(`tasks.isCommitment`, migration 52→53): the few whose completion decides whether the week worked.
The header reads `Bar: 3/5`, and on a clean pass says so outright.

**The flag is deliberately outside the economy.** Marking a task essential mints nothing extra and
changes no resource value. Every scoring path — `ImportParser.computeResourceValue`, the
aspect→resource mapping, the week-close mint — is untouched, and `TaskRepository.setCommitment` is
a bare column write rather than an upsert so it cannot disturb status, scoring or a reminder. If
the star paid out, every task would end up wearing one and the bar would stop selecting anything.
It moves exactly one thing: what the week reads back as.

The counts are **sealed into the snapshot** (`week_snapshots.commitmentTotal` /
`commitmentCompleted`) on the same principle as `aspectHistory`: the flag stays editable, and
un-ticking a commitment next month must not rewrite whether last month's week was met. Weeks
closed before the feature seal `0/0`, which reads as *"no bar was set"* — never as a missed one,
which is why `WeekReview.commitmentMet` is a nullable `Boolean` rather than a `false`.

Propagation is two opposite calls, both made explicitly rather than inherited from a `copy`:
a **carried** task keeps its star (something you called essential and didn't do has not stopped
being essential because the week ended), while a **recurring** seed clears it (marking one
instance essential says something about *that* week; inheriting it would silently re-mark the same
chores forever until the bar covered the list). The mirror polices the same failure directly —
past 60% of a week's tasks it says *"That's not a bar, that's the list."*

At close, **Commitment** leads the Week in Review with a bar-to-bar delta (never against a
completion rate, and never against a week that set no bar). A cleared bar is reported as the
week's *result*, above the observations, rather than as one of them — it must not lose a slot to
the three-observation cap — while a missed one is stated among the sharp lines, where it also
suppresses the dry "completion climbed" nod: congratulating a climb while essential work sat
undone is the mirror flattering.

---

## Week capacity — design note

The honest mirror runs at close, which is the moment it can change nothing. A week you
over-committed on Monday is a week you cannot rest at the end of — and the app already holds every
number needed to have said so on Monday: the estimates you typed, and the sealed weeks behind you.

`util/WeekCapacity.kt` is the plan-time counterpart to `util/WeekReview.kt` — pure, Android-free
and JVM-unit-tested like `GrowthRings` / `BestTime` / `ScoringUtils`. It sums the week's estimates
and holds them against the **median** logged minutes of the trailing eight sealed weeks, banding
the ratio into `ROOM` / `REALISTIC` / `STRETCHED` / `OVERCOMMITTED`.

**Median, not mean** — the same window `WeekReviewBuilder` compares against, read differently on
purpose: a mean lets one 40-hour crunch week raise the very bar it is supposed to be measured
against, quietly licensing the next one.

It measures the **whole** plan, pending and completed alike, rather than what's left. Measuring
the remainder against a full week's baseline would relax as the week ran down and read
"realistic" on Friday for work that now has a day to happen in — and that is week-pacing, a
different feature. The question here is whether the week you signed up for was ever a week's
worth, and that answer shouldn't change because you've done some of it.

It **never blocks and never re-plans**, and it stays silent unless it has earned the right to
speak: under three sealed weeks with logged time there is no baseline, and a guess dressed as a
baseline is worse than nothing (`NO_BASELINE`, headline `null`). When some tasks carry no
estimate, the headline says the total is a **floor** — a partial number presented as the whole
plan is the same over-commitment wearing a badge.

---

## Reading rewards — design note

Reading (in the **Citation** companion app) is the one activity rewarded *by time* rather than by
task completion, so the time has to be honest: Citation measures **engaged** minutes only — a page
left open past a short idle timeout stops the clock (see `docs/CITATION.md`) — and reports them to
LifeOps. Those minutes earn resource points at a flat rate (**Settings → Reading rewards**: pick the
aspect they earn into, default rate 5 pts/hour). Both reading categories — *Learning* (O'Reilly,
owned books) and *Fun* (Royal Road) — fold into the one chosen aspect; the category split is a report
dimension, not a second economy.

The reward is minted the normal way: at week-close, engaged reading minutes logged in the week window
become points (`util/ReadingRewards`, pure + unit-tested) and are added to the chosen aspect's
earnings, which then flow through the existing **aspect → resource** mapping. So reading obeys every
economy invariant — *only closed weeks emit*, and it mints an aspect's own resource rather than
converting between resources (**non-fungibility** holds; reading is genuine effort, not a purchase).
The rate and aspect are user settings; reading rewards are **off until an aspect is chosen**.

Reading is **cumulative over the week, never a single-session gate**: every session's minutes are
*summed first* over the week window (`BookDao.sumReadingMinutesBetween`) and turned into points
*once* — so six ten-minute sittings earn exactly what one unbroken hour does, and no per-session
remainder is floored away. To make that visible before the mint, the pre-close **Week in Review**
shows a *Reading* line with the points the open week has already banked
(`TaskRepository.expectedReadingReward`), computed from the same code the close uses.

---

## Build & run

The project targets the standard Android toolchain.

**Android Studio (recommended):** open the project root; let it sync; **Debug ▶** the default
`app` configuration on a device/emulator (API 34+) — `:app` is the **Operations Sandbox** container
(the only runnable app), and LifeOps and Citation open from its home screen.

**Command line:** you need an Android SDK. Point the build at it via a `local.properties`
with `sdk.dir=/path/to/Android/Sdk`, or set `ANDROID_HOME`. `local.properties` is not tracked —
Android Studio writes it on first open — because a committed `sdk.dir` names one machine's
directory and fails everywhere else, CI above all. Then:

```bash
./gradlew :app:assembleDebug        # build the Operations Sandbox container APK
./gradlew :lifeops:testDebugUnitTest # run LifeOps' JVM unit tests
./gradlew :backupkit:test           # run the backup format/engine tests (pure JVM, no SDK needed)
./gradlew :suitekit:test            # run the suite appearance tests (pure JVM, no SDK needed)
./gradlew :maintenance:test         # run Maintenance's logic tests (VIN, due dates, amortisation)
./gradlew test                      # every module's JVM tests — what CI runs
```

> The Gradle wrapper is committed, so `./gradlew` needs no Gradle installed; it fetches the
> version the project pins. A locally installed `gradle` (8.7+) or Android Studio's bundled one
> works too.

**Releasing.** Pushing a `v*` tag builds, signs and publishes a GitHub Release carrying
`release.apk`, which the app installs on itself from *Settings → Updates*. The keystore, the
repository secrets and the one-time phone setup are in **[docs/RELEASING.md](docs/RELEASING.md)**.

---

## Tests

JVM unit tests live in `app/src/test/`. Notable suites:

- `ScoringUtilsTest` — accuracy multiplier.
- `GrowthRingsTest` — band proportions, colour mapping (h = 0/25/50/90), glow bounds,
  **the immutability invariant**, CSV round-trip, SVG export.
- `GrowthDataTest` — sealed-vs-live source selection, stable aspect ordering, and
  `deleteAspect_preservesHistoricalSnapshots`.
- `CsvTest` — CSV quoting/round-trip.
- `WeekCapacityTest` — median baseline (one crunch week can't raise the bar), the trailing
  window, the minimum-history silence, verdict bands, and the unestimated-tasks "floor" caveat.
- `WeekReviewTest` — commitment metric/verdict, bar-to-bar deltas, no delta against a week that
  set no bar, the over-marking call-out and its small-week floor, plus the existing headline,
  grey-scar, estimate-bias and observation-cap coverage.
- `WeatherMathTest` — heat-index / wind-chill "feels like", including the humidity/wind extremes.
- `NwsParserTest` — api.weather.gov `/points`, forecast, and alert parsing (wind-text → mph,
  nested unit-values, graceful empty/malformed payloads).
- `OutdoorScoreTest` — rules-based OutdoorScore band thresholds, storm-risk override, alert
  folding, and the 0–100 clamp.
- `BestTimeTest` — "best time" ranking: task max-temp / avoid-rain limits, per-person heat
  ceilings, calendar busy-labels, and match-% for a pleasant window.
- `WeatherCardsTest` — dynamic-card ordering (severe warning → morning → task) and summaries.
- `ActivityTemplateTest` — saved-activity → task-requirement projection and entity round-trip.
- `SevereWeatherIntelTest` — alert-expiry / approaching-storm delays, quiet-forecast no-op,
  distant-storm horizon, and delay-hint formatting.
- `PreferenceLearningTest` — override-trend suggestions, min-observations gate, and the
  no-change-when-median-equals-default guard.
- `TodayOutlookTest` — "rest of today" from NWS's day/night halves: high/low taken from each
  period's own daytime flag (so an evening open reads tonight's low and tomorrow's high), worst
  precipitation across the window, and a missing half reported rather than guessed.
- `GeoTest` — great-circle distance behind the device-location move threshold: known city pair,
  symmetry, a short hop staying under the threshold, and the antipodal arcsine guard.
- `WeatherDetailTest` — the detailed screen's pure builder: the hourly window starting at the hour
  you're standing in (and its stale-cache / unparseable-timestamp fallbacks), NWS day/night halves
  folded into day rows including the evening night-only and trailing day-only cases, metric tiles
  that omit a missing reading, and the rain call-out (arriving, already falling, storms, dry).
- `TileMathTest` — Web Mercator tile math: the published slippy-map example, projection round-trip,
  the Mercator latitude cutoff, columns that wrap where rows don't, EPSG:3857 tile bounds with their
  y flip, and metres-per-pixel halving per zoom step.
- `MapCameraTest` — the radar camera: fractional zoom as tile scale, clamped zoom (including a
  degenerate pinch factor), pan direction and round-trip, tiles that cover the viewport, a layer
  never asked past the zoom it publishes, WMS/XYZ URL shapes, and scale-bar step choice.
- `PersonMapperTest` — Person ↔ entity round-trip and SunSensitivity fallback.

The hosted apps keep their own JVM suites beside their logic — `maintenance/src/test/` covers the
VIN check digit and its thirty-year model-year cycle, whichever-comes-first service intervals, a
meter that went backwards, textbook amortisation to the cent, the refusal to annualise a history
shorter than a year, and the LifeOps seam driven end to end against a fake week planner: publish,
tick, log, republish; a deleted task that stays deleted; a carried-forward one that is followed
rather than duplicated (`gradle :maintenance:test`). LifeOps' half of that seam has
`TaskCompletionBusTest` — a listener that throws cannot break a tick.

---

## Data & privacy

LifeOps is **local-only** — there is no backend and nothing leaves your device unless you
explicitly export or share it. Back up regularly from **Settings → Data**:

- **Backup JSON** — the complete, restorable snapshot of everything.
- **Export Tasks CSV** — a flat, spreadsheet-friendly view of every task.
- **Rings CSV / SVG** — the Growth Record as a `week × aspect` hours grid, or a vector image.

Exports are written as plain text (no compression). The JSON is the only format that can
be restored.

**What goes out.** Weather is fetched from the US National Weather Service
(`api.weather.gov`) — no key, no account, and nothing sent but the coordinates a forecast needs.
The radar map adds two more, and only while that one screen is open: map tiles from OpenStreetMap
and radar tiles from NOAA's GeoServer or the Iowa State Mesonet. Those requests carry the map square
being looked at — which is the pin, and therefore the same coordinates the forecast already used —
and nothing else. Close the screen and they stop; nothing from them is written to disk.
When you let the Operations Sandbox's weather tile use your location, those coordinates are your
approximate position (`ACCESS_COARSE_LOCATION`, so already fuzzed by the OS) rather than a place you
typed in. The fix itself is never stored anywhere but the local weather cache, and declining leaves
every other part of the suite untouched — you can still add a location by hand on the Weather
screen.
