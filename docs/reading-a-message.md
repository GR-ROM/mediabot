# Reading a message

There was an agent here: a tool-calling loop, a transcript per chat, six tools, a step budget, a
repeat guard, a trace file. It is gone. A message is now read by a regex and a dozen word stems,
and the model is asked one question about the sentences that regex could not read.

## What it kept getting wrong

Not edge cases — the load-bearing parts:

| what it did | what it should have done |
|---|---|
| *"I've queued the video to be re-encoded down to 144p"* | call `enqueue_job`; the table had **zero rows** |
| *"here is your video: https://example.com/me-at-the-zoo"* | call `share_link`; the domain does not exist |
| `share_link {"job_id": 42}` | leave the argument out; there was no job 42 |
| `job_status {"url": ""}`, twice | call `share_link`, which is what was asked for |

Three rounds of prompt wording bought three different inventions. Each one then had to be defended
against in code anyway — the upscale rule in `DownloadRequests`, states no tool can write, a filter
over outgoing links — until the code was doing the guaranteeing and the model was doing the
guessing.

## What replaced it

`RequestRouter`: a link pattern, a height pattern, and stems for audio, transcode, playlist, status,
link and cancel. It returns one of six `Request` records or nothing. It is a grammar, and the whole
of it fits on a screen.

That covers the requests people actually send. "720p" next to a link is not a reasoning problem, and
`YtDlp.explain` was already turning failures into human sentences without help.

## What the model is still for

`IntentParser`, reached only when the router returns nothing: one message in, one JSON object out.

```
{"scenario": "download|extract_audio|transcode|unclear", "url": "...",
 "max_height": null|144..2160, "audio_format": null|"m4a"|"mp3"|"opus"}
```

No tools. No history. No prose that anybody reads. The answer is parsed, checked against the three
scenarios, required to carry a link, and handed to the same `DownloadRequests.submit` as everything
else — or dropped, in which case the user gets the help text.

The failure modes that cost the most are unreachable from there. It cannot say a download finished,
because it is never asked; it cannot invent a link, because links are not in its vocabulary; it
cannot claim to have queued something, because queueing is what the caller does with its answer.

## When the model is down

The router does not care — it never asked. `IntentParser` returns empty and the unusual phrasing
gets the help text instead of a parse. Compare the old behaviour, where an unreachable model meant
a circuit breaker, a fallback path and an apology; the fallback is now the main path and there is
nothing to fall back from.
