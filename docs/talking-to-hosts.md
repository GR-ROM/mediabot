# Talking to hosts

Field notes. This is the half of the system that changes without anybody touching the
repository, so it is written down with dates and symptoms rather than as timeless
advice.

Everything below was established by running against real hosts on 2026-08-08.

## yt-dlp is delegated to, not reimplemented

These sites change their markup constantly and yt-dlp is the thing that keeps up. What
is added on top is the part a bot needs and a command-line tool does not: a size known
before the download, progress while it runs, and failures turned into sentences a
person can act on.

Every invocation is a `List<String>` handed to `ProcessBuilder`. Never a string split
by a shell, because the URL comes from a chat message and the arguments around it are
chosen by a language model.

## What YouTube wants, as of August 2026

**Both** of these, or you get nothing:

**1. Cookies.** An anonymous request comes back as *"Sign in to confirm you're not a
bot"*. `--cookies-from-browser` is the other documented route and did not work here:
Firefox was not installed, and a running Chromium keeps its cookie database locked
([yt-dlp #7271](https://github.com/yt-dlp/yt-dlp/issues/7271)). A cookies.txt exported
from a signed-in session works.

**2. A JavaScript runtime.** With cookies alone, YouTube hands out an *"n challenge"*
that has to be executed. Without a runtime yt-dlp reports:

```
WARNING: n challenge solving failed: Some formats may be missing.
ERROR: No video formats found!
```

That pair is the trap. The cause is a *warning*; the visible failure says "no
formats", which reads as a video with nothing to download rather than a missing
dependency. Two further details cost time: the runtime is named `node`, not `nodejs`,
and it needs an explicit path, because yt-dlp searches the PATH the process actually
inherits — which on Windows is not the one a shell displays.

```
--cookies <file> --js-runtimes node:C:\...\node.exe
```

**Keep yt-dlp current.** A build from February failed against YouTube in August on its
own, before either of the above mattered. It goes stale in weeks, which is also why
the binaries are gitignored rather than committed: a pinned copy is a copy that will
be wrong.

Both settings are applied to *every* invocation including `probe`, because both
failures happen at the metadata step. Cookies on the download alone would leave the
bot unable to say what a video even is. A missing one is a warning at startup rather
than a discovery on the first message.

Other hosts need none of this. Rutube answered anonymously throughout.

## The live stream that ran for seven minutes

The first live test hung. The URL picked at random turned out to be a channel's
rolling broadcast: `is_live: true`, duration 0. A stream has no end, so downloading
one runs until the timeout kills it half an hour later, holding a worker the whole
time.

The check existed — `DownloadRequests` refuses a live URL up front — but only when
probing succeeded. A probe that times out was exactly the gap.

So there is a second line now: `--match-filter !is_live` on the download itself, which
makes yt-dlp refuse rather than trying. Its message, *"does not pass filter"*, means
nothing to anybody, so it is translated like the rest.

The general shape: a check that depends on an earlier step succeeding needs a
backstop at the point of action.

## Failures are translated

`YtDlp.explain` turns stderr into one sentence. Not decoration — the alternative is
several lines of traceback and a URL in a chat window, which reliably reads as "the
bot is broken" whatever it actually says.

The cases worth naming are the ones that recur and have *different* answers for the
person asking: geo-blocking cannot be worked around from here, a private video never
will be, a members-only video needs a subscription, an age-restricted one needs
cookies. Anything unrecognised falls back to the last line of stderr, which is where
yt-dlp puts the actual error after however much warning noise precedes it.

One of these was wrong and a test caught it. The check was for the substring
`not available in your country`; yt-dlp writes *"The uploader has not made this video
available in your country"*, where the negation sits earlier in the sentence and the
substring never matches. Now it matches on `available in your country`.

## Sizes are quoted, never estimated

`MediaInfo.estimatedSize` adds the video stream that *will be fetched* to the audio
stream that will be muxed with it, because these hosts serve them separately and
muxing produces roughly the sum. Where a host publishes no figures it returns empty,
and the bot says so.

"Will be fetched" is the whole of it, and getting it wrong is not a rounding error.
YouTube publishes the same 720p three times over — H.264 167 MB, VP9 91 MB, AV1
65 MB — so *the largest stream under the ceiling* and *the stream the downloader
takes* can differ by a factor of two and a half. The bot announced 179 MB and
delivered 81. Both the estimate and the download now go through `pickVideo` /
`pickAudio`, so there is one rule rather than two that agree by luck.

Rutube publishes none. The report reads `no figure` rather than a plausible number,
which is the right answer: a size quoted to a person is a size they will plan around.

## Format selection, and the merge

The streams are chosen here and fetched one at a time, the way `yt-downloader` does
it, rather than described to yt-dlp and left to it:

```
pickVideo  tallest under the ceiling → most frames → H.264 before VP9 before AV1
pickAudio  AAC before Opus → highest bitrate
```

then `ffmpeg -c:v copy -c:a copy -movflags +faststart` into one mp4. Copies, not
re-encodes: the quality asked for is the quality delivered, and the merge costs
seconds rather than minutes.

Both tie-breaks are about what a Telegram client can play. Left to itself yt-dlp
prefers AV1 video and Opus audio — half the bytes, and an mp4 carrying Opus arrives
on Apple's decoders as a video with no sound at all. H.264 plus AAC plays inline
everywhere, which is what `--merge-output-format mp4` was reaching for and did not
reliably get.

The tie-break flips when the file is not the one anybody will watch. `pickVideo` takes
a `Purpose`: `DELIVERY` prefers H.264 as above, `RE_ENCODING` prefers AV1 and, between
equal codecs, the smaller file. A transcode job re-encodes everything to H.264 anyway,
so nothing about the fetched codec survives — the only thing that still matters is how
many bytes have to arrive first, and on the video above that is 111 MB instead of
312 MB for the same 1080p. Height still comes first in both: a taller AV1 stream never
loses to a shorter H.264 one.

The audio pick does not flip. Opus would be smaller, but the merge would transcode it
to AAC to get into the mp4 and the re-encode would do it again — two lossy passes over
the same track to save a few megabytes.

Three ways out, all of them still producing a file: a host with no separate streams
is taken whole through `pickProgressive`, a probe that would not answer falls back to
yt-dlp's own selector, and so does a deployment with no ffmpeg configured — logged as
a warning, because the result is a lower quality than was asked for.

Re-encoding is deliberately rare. A host publishing several heights should be asked
for the right one — no CPU, no loss. `Ffmpeg.scaleTo` is for a host that publishes
exactly one stream, Instagram chiefly, and for a file that came back too big to send.

## Reading the process

`ProcessRunner` splits on `\r` as well as `\n`. Both yt-dlp and ffmpeg redraw a
progress line in place with a carriage return and no newline, so a plain `readLine`
blocks until the process ends and then delivers the entire progress history as one
line — that is, no progress at all.

Both streams are drained on their own virtual threads. A process whose stderr pipe
fills while the reader is busy with stdout blocks forever, and looks exactly like a
slow download.

Cancelling a task does nothing to the process it started, so an interrupt destroys the
subprocess explicitly. Without that, a cancelled download keeps downloading —
invisible, unattributable, and finishing into a directory nobody will read.
