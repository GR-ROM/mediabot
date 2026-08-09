# When the model is down

The bot runs against a local model host on a machine with no GPU. It will be down
sometimes, and the interesting question is not how to prevent that but what the bot
should be while it lasts.

The answer taken here: **still a downloader.** Not an apology.

## Two layers, because they answer different failures

`ModelHttp` retries inside a single call — three attempts, backoff, jitter, and an
early exit when an attempt has already burned half its timeout, since retrying a hang
multiplies the most expensive thing in the system. That is the right answer to a
dropped connection, which fails in milliseconds and succeeds on the second try.

It is the wrong answer to a host that is simply off. Then every message pays three
attempts and a timeout each before failing, and a person waits minutes to be told the
bot cannot think.

So `LlmAvailability` sits above it. Two consecutive failures and the model is skipped
outright; the wait doubles from 20 seconds to a 5-minute cap, jittered so that several
chats which failed together do not come back together and hammer a host that is only
just up.

Capped rather than unbounded, and the reason is worth stating: the host coming back is
an event nothing notifies us about. An ever-growing backoff would leave the bot in
fallback long after the model was healthy. A single probe every few minutes is cheap,
and the cost of being wrong in that direction is one slow message.

## What the fallback actually does

`RequestRouter` reads a message without a model. It recognises a link, a height, and a
request for audio. It recognises nothing else.

That narrowness is the design. There is no attempt to guess at "the one from
yesterday" or "same as before", because a wrong guess here is a download nobody asked
for, and the honest answer when it finds nothing is to say the model is unavailable
rather than to invent an interpretation.

When it does find something, the bot serves it and says how it read the message:

> The language model is unavailable (retrying in about 140 s), so I read your message
> literally.
>
> Queued as job 12 (up to 720p). I will send it here when it is ready.

Stating the reading is what makes it correctable. A person who meant something else
can see immediately that they did, which an apology would not have told them.

## It is not a second implementation

The fallback queues through `DownloadRequests`, exactly as the tools do. Every rule
still applies — the allowlist, the live-stream check, the refusal to pretend a 480p
source can be 720p.

This is the whole reason those rules are in a shared class rather than in the tools.
A fallback path with its own weaker checks would be a second implementation that
diverges, and it would diverge in the direction of the path nobody tests, which runs
only when the model is already broken.

## The same mechanism serves the common case

A message that is one bare link goes straight to the queue even when the model is
perfectly healthy. It is most of the traffic, and there is nothing in it to interpret;
paying a round trip to a model to discover that would be the slowest part of the
commonest request there is.

The fast path and the fallback are the same code, reached for different reasons. That
is worth noticing: the deterministic router is not dead weight waiting for an outage,
it runs constantly, which is also why it is unlikely to be broken when the outage
arrives.

`telegram.fast-path: false` sends bare links through the model too, for anyone who
would rather have the model see everything.

## Where a callback lands during an outage

A job that fails while the model is down still has to be reported — a failed download
nobody is told about is the worst outcome available here.

So `ChatDispatcher.wakeAgent` checks availability first, and falls back to a plain
sentence assembled in code. Less graceful than what the model would have written, and
present, which is the trade that matters.

## What is not defended against

A model that is *up* and answering nonsense. The loop's repeat guard and step ceiling
bound how much damage that can do, and the tool-level checks mean the damage cannot
include an upscaled file or a fetch from inside the network. Beyond that, a model that
routes badly produces a wrong download, and the answer is a better model or shorter
tool descriptions — not more machinery.
