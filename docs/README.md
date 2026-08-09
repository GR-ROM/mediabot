# Design notes

The code says what happens. These say why, and what was tried first.

The project README is the front door: what the bot is, how to run it, what YouTube
demands this month. These are the arguments behind the parts that were not obvious,
written out at length so the code itself can stay short.

| | |
|---|---|
| [The queue, and who delivers what](the-queue.md) | Why a tool that downloads is the wrong shape, and the rule that a finished job is delivered by code while a broken one is delivered by the model |
| [The states of a job](the-states-of-a-job.md) | The walk from PENDING to EXPIRED, why downloading and processing are separate, and why no tool can write a state |
| [Guarantees do not live in prompts](guarantees-not-prompts.md) | The 720p-out-of-480p rule, why it is in a method rather than a system prompt, and the two callers that would otherwise disagree |
| [Reading a message](reading-a-message.md) | Why the agent was removed, what a dozen stems replaced it with, and the one thing the model is still asked |
| [When the model is down](when-the-model-is-down.md) | A circuit breaker above the retries, and a router that reads messages literally rather than apologising |
| [Handing over the file](handing-over-the-file.md) | Why nothing is uploaded any more: a short-lived link, byte ranges for a player that seeks, and the code that is the only thing guarding the file |
| [Talking to hosts](talking-to-hosts.md) | Field notes on yt-dlp: the two things YouTube wants, the live stream that ran for seven minutes, and why failures are translated |
| [Safety boundaries](safety-boundaries.md) | Two untrusted inputs meeting a subprocess, video titles that land in a model's context, and what happens to a browser's worth of cookies |

## A note on where things are written down

Three places, and the split is deliberate.

**Code comments** say what a reader of that line cannot see from the line: a
constraint, a defect that was hit, an ordering that matters. They are short by
policy — a paragraph in a method body is a paragraph nobody reads twice.

**These essays** carry the reasoning: what the alternatives were, what was measured,
what would have to change if a requirement changed. This is the material that goes
stale slowly and is expensive to reconstruct.

**The project README** is for somebody deciding whether to run the thing, and for
somebody making it work on a Tuesday when YouTube has changed its mind again.

The convention is inherited from `photo-agent`, along with most of the agent core.
So is the failure it avoids: an argument long enough to be worth keeping, written
into a comment where it was read once and then maintained by nobody.
