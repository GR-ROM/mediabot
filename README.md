# mediabot

A Telegram bot that downloads videos, driven by a small local language model.

Send it a link and it downloads it. Say what you want — "720p", "only the audio", "the first
five from this playlist" — and the model works out which tool to call. Ask for a quality the
source does not have and it tells you so instead of upscaling.

The bot answers in English regardless of what language it is written to in.

## The shape of it

```
message ─► RequestRouter ─► one of six requests ─► answered from a template
                │
                └─ unreadable ─► IntentParser ─► a JobSpec, or the help text
                                 (one JSON object; no tools, no prose)

   queue ──► worker ──► yt-dlp ──────► PENDING → DOWNLOADING → PROCESSING → DONE
                 │      + ffmpeg                                    │
                 └─ progress ─► one message, rewritten              └► a short link, then EXPIRED
```

Four decisions carry most of the design — the arguments behind them, and behind the parts
that were not obvious, are in [docs/](docs/README.md):

**Reading a message is a grammar, not a judgement.** A link pattern, a height pattern and a dozen
word stems cover what people actually send. The model is asked one question — turn this sentence
into a `JobSpec` or say you cannot — and only about the messages the router could not read. It has
no tools and writes nothing anybody reads, because when it did it announced downloads that were
never queued. See [docs/reading-a-message.md](docs/reading-a-message.md).

**Requests queue, they do not download.** Queueing returns in milliseconds and the job walks
PENDING → DOWNLOADING → PROCESSING → DONE → EXPIRED on code alone. See
[docs/the-states-of-a-job.md](docs/the-states-of-a-job.md).

**Success is delivered by code; problems are delivered by the model.** Handing over a finished
file must not depend on a small model remembering to call a tool, and it must not cost a model
round per download. Explaining a geo-block and offering an alternative is exactly what the model
is for. See `ChatDispatcher.onJobFinished`.

**Files are handed over as links, not uploads.** The finished video moves into a served
directory and the chat gets a short URL that stops working after a day. An upload has to fit
whatever the Bot API will take — 50 MB on the public one — and spends the bot's bandwidth a
second time; a link costs nothing until somebody clicks it, so a two-gigabyte download is no
harder to deliver than a small one. See [docs/handing-over-the-file.md](docs/handing-over-the-file.md).

**Guarantees live in code, never in the prompt.** The rule that you cannot have 720p out of a
480p source is enforced in `DownloadRequests.upscaleRefusal`, not in an instruction. The model
writes the sentence the user reads; it does not decide whether the sentence is true. This matters
most when the model is unreachable — which is exactly when a prompt-based rule would be gone.

## When the model is down

Almost nothing changes, which is the point of the shape above. `RequestRouter` never asked it
anything; `LlmAvailability` keeps `IntentParser` from waiting on a host that is not answering, and
an unusual phrasing gets the help text instead of a parse.

## Running it

Needs `yt-dlp` and `ffmpeg` binaries, and an OpenAI-compatible model endpoint with tool calling.

```bash
export MEDIABOT_TG_TOKEN=...          # from BotFather
export MEDIABOT_TG_USERNAME=my_bot
export MEDIABOT_ALLOWED_CHATS=123456  # or anyone who finds it can spend your bandwidth
export MEDIABOT_LLM_URL=http://192.168.1.104:11434/v1
export MEDIABOT_LLM_MODEL=granite4:tiny-h
export MEDIABOT_LINK_BASE=https://media.example.com   # what the user clicks — not localhost

./gradlew run
```

The bot also listens on `MEDIABOT_PORT` (8099 by default) to serve finished downloads. That
address has to be reachable from the user's phone, so `MEDIABOT_LINK_BASE` is the one setting
that cannot be left at its default in a real deployment.

Everything except the chat transport works without a token, so a config mistake is a message on
the console rather than a bot that starts and answers nothing.

### YouTube needs two things

Worked out the hard way, and it takes **both** or you get nothing:

```bash
export MEDIABOT_COOKIES=/path/to/cookies.txt
export MEDIABOT_JS_RUNTIME='node:C:\path\to\node.exe'
```

**Cookies**, because an anonymous request comes back as *"Sign in to confirm you're not a bot"*.
Export a cookies.txt from a signed-in session. `--cookies-from-browser` is the other documented
route but did not work here: a running Chromium keeps its cookie database locked
([yt-dlp #7271](https://github.com/yt-dlp/yt-dlp/issues/7271)).

**A JavaScript runtime**, because YouTube then hands out an *"n challenge"* that has to be
executed. Skip it and yt-dlp reports `n challenge solving failed` as a *warning* and returns an
empty format list — which surfaces as "No video formats found", reading like a video with
nothing to download rather than a missing dependency. Give it an explicit path: yt-dlp searches
the PATH the process actually inherits, which on Windows is not the one your shell shows you, and
the runtime is named `node`, not `nodejs`.

Both are passed to every yt-dlp invocation including the probe, since both failures happen at
the metadata step. Missing either is a warning at startup rather than a discovery on the first
message. Keep yt-dlp current too — a build a few months old fails against YouTube on its own.

Other hosts need none of this; Rutube works anonymously.

### The 50 MB problem

Telegram's public Bot API refuses any upload from a bot over 50 MB, which rules out most of what
this is for. `docker-compose.yml` runs the same server locally and lifts that to 2 GB; the
instructions are in the file. Then:

```bash
export MEDIABOT_TG_API=http://localhost:8081
export MEDIABOT_MAX_UPLOAD=2097152000
```

Without it the bot still works — it just reports files over the limit instead of sending them,
and offers to fetch a smaller height.

## Configuration

Every knob is in `application.yml` under `mediabot.`, and each section validates itself at
startup, so a value that cannot work names itself instead of surfacing as an NPE somewhere else.
The ones worth knowing:

| Key | Why you would touch it |
|---|---|
| `media.allowed-hosts` | The security boundary. A URL arrives in a chat message; this is what stops it pointing the bot at your network |
| `jobs.concurrency` | Downloads at once. Mostly network, but the muxing at the end is not |
| `links.base-url` | What the user clicks. The one setting that cannot be left at its default |
| `links.ttl-hours` | How long a link keeps working before the file is deleted |
| `server.port` | Where the files are served from, 8099 by default |

## What came from where

`ProcessRunner` and the two-stream download-then-merge pipeline come from `yt-downloader`; the
yt-dlp wrapper began as `insta-dl`'s. The agent core was ported from `photo-agent` and has since
been removed — what it was for, and what it kept getting wrong, is in
[docs/reading-a-message.md](docs/reading-a-message.md).

## Tests

```bash
./gradlew test
```

The ones that matter: `UpscaleRefusalTest` holds the central guarantee with no model and no
network anywhere near it, `AgentLoopTest` drives the loop with a scripted model to check the
third identical call is refused and a thrown tool becomes text, and `UrlGuardTest` covers the
hosts that must not get through.

`LiveMediaTest` is the other half — a real host, a real file on disk. Opt-in, since it needs
the network and the binaries:

```bash
./gradlew test --tests '*LiveMediaTest' \
  -Dmediabot.live='https://www.youtube.com/watch?v=jNQXAC9IVRw' \
  -Dmediabot.cookies=/path/to/cookies.txt \
  -Dmediabot.js='node:C:\path\to\node.exe'
```

Everything the ordinary suite covers is a claim about the code; this checks the claims match
what a hosting service actually sends back, which is the half that changes without anybody
touching this repository. Drop the last two properties for a host that answers anonymously.
