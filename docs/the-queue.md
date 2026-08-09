# The queue, and who delivers what

A download is minutes. Everything here follows from refusing to spend those minutes
inside a tool call.

## Why a tool that downloads is the wrong shape

The obvious design is a `download_video` tool that downloads a video. It fails in
four ways at once, and none of them are visible until the thing is running.

The model round stays open for the whole download, so the HTTP timeout to the model
host has to be longer than the longest video anybody will ever send. The chat's lock
is held throughout, so the person cannot ask anything else — including "stop". The
process cannot be restarted without losing work that somebody is still waiting for.
And the transcript ends up holding the result of an operation that took ten minutes,
which the model will happily reason about as though it had just happened.

So requests queue. Queueing returns a job id in milliseconds, and the
model's turn ends. What happens afterwards is the queue's business, and the person is
told about it by whatever mechanism suits the outcome.

The cost is that the model has to believe the file will arrive without it doing
anything else. Small models do not believe this by default: a tool that answers
"queued" reads as an invitation to call it again. Both the tool description and the
system prompt say outright that the file is delivered by the system, that calling
again is wrong, and that the correct next move is one sentence and silence.

## The table is the queue

`enqueue` writes a row and wakes a worker. That is all it does. `JobWorkers` runs a
small number of threads that claim rows and download them, and the claim *is* the
state change: the move from `PENDING` to `IN_PROGRESS`, which exactly one worker can
win. `JobClaimTest` holds that property down, because it used to be free — one
`submit` meant one runner by construction — and is now a claim about locking that
would otherwise fail as a video downloaded and sent twice.

An idle worker is parked, not polling. `LockSupport.unpark` on enqueue starts it
immediately, and a permit cannot be lost: a worker unparked a moment before it parks
does not park. The thirty-second park timeout is a safety net for a wake-up that never
came, so the worst case is a late download rather than one that never happens.

The reason to split this out of `JobQueue` at all: queueing and running were welded
together, which made a job half a row and half a task in an executor, and the two
could disagree. A task lost meant a row stuck at `PENDING` with nothing to notice it.
A row cancelled meant a task that ran anyway. Now nothing about a job lives only in
memory, and "what is this process doing?" is a query.

## What survives a restart, and what does not

Jobs are rows in SQLite before they are anything else. Not because the work survives —
it does not, a killed process kills its yt-dlp — but because the *promise* survives,
and that is the thing worth keeping.

On startup, anything still marked `PENDING` or `IN_PROGRESS` becomes `INTERRUPTED` and
the chat is told. Deliberately not resumed. An hour after a crash the video is usually not wanted
any more, and re-downloading it unasked spends real bandwidth on a guess about
somebody's mood. But leaving a person waiting forever for a file that nothing is
producing is worse than either option, and that is what an in-memory queue does.

The announcement happens in `BotRegistration`, after the bot is online, because
announcing needs something to announce with. Publishing an event at the moment the
store is built would put it in an empty room.

It also has to happen before any worker starts claiming, or a worker picks up last
run's abandoned row and downloads it behind the message saying it was abandoned. That
ordering is not a comment: `BotRegistration` is an `ApplicationRunner` and the workers
start on `ApplicationReadyEvent`, which Spring publishes after the runners have run.

## The delivery rule

This is the decision the whole chat side is arranged around:

> **A finished job is delivered by code. A broken one is delivered by the model.**

On success the file is published and its link posted to the chat, and the agent is
never woken. Two reasons, and both are about not making delivery contingent on
inference. A model that forgets to call a delivery tool loses a file somebody waited
ten minutes for — silently, with no error anywhere. And a model round per download
would make the largest cost in the system fall on the case where there is nothing
whatever to decide. How the link itself works is
[its own note](handing-over-the-file.md).

On failure the event goes into the session as a turn. Here the model is doing what it
is actually good for: turning "the uploader has not made this video available in your
country" into a sentence, and noticing that a 480p version exists and offering it.
Same for a file that came back larger than the bot will hand over, and for a
publication that failed with the download already on disk.

`ChatDispatcher.onJobFinished` is a switch over `JobState` for exactly this reason.
The three ways of not succeeding are separate states rather than one `FAILED` with a
message, because each has a different answer for the person waiting, and code that
routes them should switch on a value rather than read a string.

## Where the callback lands

The event arrives on the worker thread that finished the job, and takes the chat's
lock before touching its transcript. That is what stops a callback splicing itself
into the middle of a tool round — the same lock a message takes, held for a whole
request rather than around each mutation.

A turn woken by an event gets `loop.event-max-steps`, which is smaller than the
budget for something a person asked. A model woken by a failed download has one job:
explain it and offer a way forward. A full budget there is an invitation to start
queueing things nobody asked for.

## Progress

Reported by the worker straight to the chat through `ProgressSink`, never through the
model. One message per job, rewritten in place, at most one edit every four seconds
and only when the bar has moved five percent.

The throttle is not politeness. Telegram rate-limits edits per chat; a download
reporting every percent issues a hundred of them, gets limited, and then fails to
deliver the messages that actually matter. Nobody watching a download needs it
smooth. They need to know it is alive.

## The interface that keeps the dependency acyclic

The chat side has to depend on the queue in order to enqueue anything. So the queue
must not depend on the chat side to report back. Two mechanisms keep that true:
`ProgressSink` is an interface the queue owns and the Telegram layer implements, and
completion is a Spring event rather than a listener list injected into `JobWorkers`.

Reverse either and the context stops building — which `ContextLoadsTest` checks,
because a cycle is not something the compiler will tell you about.
