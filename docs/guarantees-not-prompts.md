# Guarantees do not live in prompts

The rule this project was really built around is small enough to state in a sentence:
**you cannot be given a height the source does not have.**

Ask for 720p from a video that only exists in 480p and you should be told so, and
offered what is actually there. Not handed an upscaled file that is larger than the
original and worse than the original.

Where that rule lives turns out to matter more than the rule itself.

## The version that does not work

The obvious implementation is a paragraph in the system prompt: *"Before queueing,
probe first. If the requested height is higher than the source, refuse and offer
what is available."*

This works most of the time, which is the problem. A 4-billion-parameter model
comparing 480 and 720 gets it right often enough to look correct in testing and to
fail in production, and the failure is silent — a file arrives, it is just the wrong
one. Worse, the rule evaporates in exactly the conditions where it matters: when the
model server is unreachable and something else has to do the queueing.

A guarantee that holds only while the model is up and paying attention is not a
guarantee. It is a tendency.

## Where it actually lives

`DownloadRequests.upscaleRefusal` — a method, taking a requested height and a
`MediaInfo`, returning either null or a sentence. Nothing about it is agentic. It is
called on the way into the queue, from every path that leads there.

The line this draws — *the fact is code, the wording is a template* — is the general
shape of everything else here. It was once *the fact is code, the phrasing is the
model*, which held right up until the model started phrasing facts nobody had given
it; [reading a message](reading-a-message.md) is what came of that.

## The three callers, and why one class

There are three ways a download gets queued: the router reading a message literally,
the parser turning an unusual sentence into a `JobSpec`, and a playlist fanning out
into several. If the checks lived in any one of them the other two would have none.

So all three go through `DownloadRequests.submit`, which takes a whole `JobSpec` and
answers with an `Outcome`. None of them can skip the rule, because none of them
implements it.

This also covers the live-stream check, which has the same shape: a stream has no end,
so "download it" is not a request that can be satisfied, and saying so up front is
better than a file that grows until a timeout kills it.

## The probe cache is a design element, not an optimisation

The check needs to know what the source actually has, which means a probe. A probe is
a few seconds of network. If every queued request paid that, the temptation to
skip the check "when the model already probed" would be overwhelming — and then the
guarantee is back to depending on the model.

`ProbeCache` makes the second probe free, so the check can run unconditionally on
every call. Ten-minute entries, because formats are a property of a host at a moment
and a URL probed an hour ago may since have been made private.

The failure mode is handled the other way round: if probing fails, the download is
**not** refused. yt-dlp falls back to the best available format on its own, so the
worst case is a lower quality than asked for — which is a great deal better than
refusing to download anything because a metadata call timed out.

## What it looks like when it fires

From a real run against the oldest video on YouTube, which exists only in 240p:

```
asked for 960p of a 240p source ->
  this video tops out at 240p, so 960p cannot be had from it — upscaling would only
  inflate the file without adding a single detail. Available: 240p, 144p
```

Three things in one sentence, on purpose: what the source is, why the answer is no,
and what can be had instead. A refusal without the third part leaves the person with
nowhere to go, and leaves the model nothing to offer.

## Testing it

`UpscaleRefusalTest` builds `MediaInfo` values by hand and calls the method directly.
No Spring, no network, no model — and that is the point, since the rule is supposed to
hold with none of those present.

`LiveMediaTest` does the other half: it probes a real host and checks the rule against
whatever that host actually said. The unit test proves the rule is applied correctly;
the live test proves it is applied to the truth. Both matter, and the second is the
one that catches a change in how a host reports its formats.
