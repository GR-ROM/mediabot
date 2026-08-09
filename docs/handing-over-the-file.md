# Handing over the file

The bot does not upload anything. A finished download moves into a served directory and the
chat gets a short URL that stops working after a day.

## Why not upload it

The public Bot API caps an upload at 50 MB. A twenty-minute video at 720p is 179 MB, so the
cap is not an edge case — it is most of what anybody asks for. Running a local
`telegram-bot-api` raises the ceiling to 2 GB, but it is another moving part to deploy, and the
upload still spends the bot's bandwidth a second time: once to fetch the video, once to push it
to Telegram, whether or not the person ever watches it.

A link costs nothing until somebody clicks it, and nothing at all if they do not. Two gigabytes
are no harder to hand over than two megabytes.

`mediabot.telegram.max-upload-bytes` survives as a guard, set to 2 GB. Nothing uploads through
it any more; it is what stops a job that produced something absurd from being handed over at
all, and it is the number `TOO_BIG` is measured against.

## What happens when a job finishes

```
work/job-7/Title (720p).mp4      the merged file, where the worker left it
        │  Files.move
        ▼
public/<code>/Title (720p).mp4   served; work/job-7 is released
        │
        ▼
https://media.example.com/d/<code>
```

`ShortLinkService.publish` does the move, writes a row, and records the new path back onto the job — a row
still pointing into a working directory that has been deleted is a path to nothing the next
time anybody reads it. That last step is best-effort: jobs are swept after 48 hours while a
link may outlive them, and a link that refused to be handed over because the job it came from
had been forgotten would be the wrong way round.

The code is 128 bits from `SecureRandom`, base64url. It is the only thing protecting the file,
which is the trade being made: no accounts, no tokens, nothing to log into, and a URL nobody
can guess. Anyone the user forwards it to can watch it, which is usually the point.

## Serving it

`DownloadController` maps `GET /d/{code}`: it resolves the code against the table, checks the
link has not expired and that the file is still there, and streams it. Two things are worth
naming:

**Byte ranges are handled explicitly.** These are videos. A player that cannot ask for the
middle of a file has to fetch all of it before it can show the middle, which for a
two-gigabyte download is the difference between seeking and waiting. Spring Boot 4 does not
register `ResourceRegionHttpMessageConverter` by default, so returning a `ResourceRegion`
answers 500 — the controller parses `Range` itself, answers `206` with `Content-Range`, and
serves one range only, which is what a player asks for when somebody drags the scrubber.

**The body is streamed, never read into memory.** `StreamingResponseBody` over a 64 KB buffer.
A controller that returned a `byte[]` would put the whole video on the heap.

## Expiry

`mediabot.links.ttl-hours`, 24 by default. Sweeping runs hourly, at startup, and before every
publish; it deletes the row and the file together. A file left behind after its link died is
one nobody can reach and nobody will notice, which is how a disk fills up over a month.

## Asking for it again

The link is posted by `ChatDispatcher.deliver` the moment the job finishes — by code, never by
the model, for the same reason as before: delivery must not depend on a small model remembering
to call a tool.

`share_link` is the second time somebody asks. A link that scrolled out of the chat, or one
that expired while the file is still on disk. It takes a job number or defaults to the most
recent finished download in that chat, and it is careful about two things: a job belonging to
another chat is not something to link to, and asking twice hands back the link that already
exists rather than publishing a second copy under a new code.

## The model cannot be trusted with a URL

Told in its prompt, in capitals, that it may never write a link it has not read, the 4 GB local
model answers *"here is your video again: https://example.com/me-at-the-zoo"*. Right shape,
right words in it, nothing behind it. Three attempts at wording the rule bought three different
inventions — one made-up domain, one made-up job number, one plausible short URL.

So the rule moved where every other guarantee in this bot lives. `LinkSanitizer` runs over every
answer the model produces before it is sent. A URL survives if it is one this bot issued and
still resolves to a live file, or if it points at a host the bot downloads from — the source
video, which the user sent in the first place. Everything else becomes `[link removed]`, with a
line telling the user why, and the fabrication is logged as a warning.

It is the same shape as `DownloadRequests.upscaleRefusal`: the model writes the sentence the
user reads, and it does not get to decide whether the sentence is true.

## Configuration

| Key | Default | |
|---|---|---|
| `server.port` | `8099` | 8080 is taken often enough to be a bad default |
| `mediabot.links.base-url` | `http://localhost:8099` | **must be changed**: it is what the user clicks |
| `mediabot.links.dir` | `./public` | where finished files are served from |
| `mediabot.links.ttl-hours` | `24` | how long a link lives |

The base URL is the one setting that cannot be left alone. A link to `localhost` works while
trying it out on the machine that runs the bot and for nothing else.
