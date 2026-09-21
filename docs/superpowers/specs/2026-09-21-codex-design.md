# A Codex provider on a ChatGPT subscription — design

FOLLOWUP.md §6. The facts come from
[the research](../../research/2026-09-21-codex-subscription-api.md), which reads the source of the
Codex CLI at commit `695ab0a`.

## Problem

konacode needs `OPENAI_API_KEY`, and a key bills per token. A ChatGPT subscription pays for the
Codex CLI, and OpenAI staff said in public that a subscriber may use the subscription in the tool
they prefer. A Claude subscription cannot pay for konacode since 4 April 2026, so this is the one
subscription route open.

The Codex backend speaks the Responses API, not Chat Completions, and it authenticates with a
token that `codex login` writes to a file. A base URL swap is not enough.

## Decision

konacode gains a second wire format and a second kind of credential. `KONACODE_AUTH=codex` selects
both. The default stays `OPENAI_API_KEY` and Chat Completions.

Four caveats, and the README states each one:

- The terms of use neither permit nor prohibit it. OpenAI tolerates personal use of your own
  subscription. A pooled account or a shared credential is not that.
- The endpoint has no SLA, and it can change with no notice.
- OpenAI recommends an API key for production work.
- Anthropic tolerated the same thing until it did not. This route can close, and
  `OPENAI_API_KEY` stays the supported one.

The research could not read the help article or the terms of use: both returned 403. The README
tells the user to read them.

## The facts that decide the design

- **Every request of the CLI streams.** It sends `stream: true` and reads SSE. The finished text
  and the finished tool call both arrive in `response.output_item.done`. `response.completed`
  ends the stream. No code sends a non-streaming request, so nothing proves that the endpoint
  accepts one. konacode sends `stream: true`, reads the whole body with `BodyHandlers.ofString`,
  and parses the events after the stream closes. `LlmClient.chat` stays blocking.
- **The refresh token rotates.** A refresh gives a new refresh token, and the server refuses a
  reused one. The CLI writes the new tokens back to `auth.json` at once. The access token carries
  an `exp` claim.
- **The headers the CLI sends:** `Authorization: Bearer`, `ChatGPT-Account-ID`, `originator`,
  `User-Agent`, `Accept: text/event-stream`, `Content-Type`, and routing headers. The code does
  not say which ones the server requires. `OpenAI-Beta` is not on the HTTP request.
- **The models.** The bundled list at that commit is `gpt-6-astra`, `gpt-5.6-sol`,
  `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5` and `gpt-5.4`. `gpt-5-mini` is not on it. Every model
  except `gpt-5.5` and `gpt-5.4` uses "Responses Lite", where the CLI moves the instructions and
  the tools into `input`. Whether the backend accepts the plain shape for those models is untested.

## Three decisions

**No refresh.** konacode reads `auth.json` once at start. When the `exp` claim is in the past,
konacode prints one line and exits 1. When the server answers `401`, the turn ends with the same
sentence. The sentence is: "Run `codex login`, then start konacode again." Codex refreshes the
file on its next run, so the fix is one command. A refresh inside konacode is a later change: it
must write the whole file back, and it must not race a Codex process on the same file.

**The default model is `gpt-5.5`.** It is on the bundled list, and it uses the plain Responses
shape. `KONACODE_MODEL` overrides it, as today. The README says how to see the live list with
`codex`. konacode does not call `GET .../codex/models`.

**No reasoning passthrough now.** konacode sends `store: false` and no `include`. No reasoning
item comes back, and the codec ignores any that does. The model reasons again on each iteration,
which is what Chat Completions does today. The passthrough field of FOLLOWUP.md §1 is the next
item after this one, and this change gives it the payload it needs.

## Components

Everything lives in `dev.konacode.llm.openai`, except the wiring in `Main`.

| Element | Kind | Definition |
|---|---|---|
| `Codec` | interface, extracted from `ChatCompletionsCodec` | `String path()`, `String accept()`, `ObjectNode encodeRequest(model, history, tools)`, `AssistantMessage decodeResponse(body)`, `Optional<Usage> decodeUsage(body)`. The codec owns the wire format. The path and the `Accept` header are part of it: `/chat/completions` with `application/json`, `/responses` with `text/event-stream`. |
| `ChatCompletionsCodec` | implements `Codec` | Unchanged, plus `path()` and `accept()`. |
| `ResponsesCodec` | implements `Codec` | Encodes a Responses API request. Decodes an SSE body. Section "The Responses codec". |
| `Credential` | sealed interface | `ApiKey(String key)` or `CodexToken(String accessToken, String accountId)`. Sealed, so a third kind is a compile error at the header switch. |
| `CodexAuth` | final class | `static CodexToken read(Path authFile, Instant now)`. Reads `auth.json`. Section "The auth file". |
| `OpenAiConfig` | record | `apiKey` becomes `Credential credential`. `fromEnvironment(Map environment, Path codexAuthFile)` reads `KONACODE_AUTH`. `chatCompletionsUri()` becomes `URI uri(String path)`. |
| `OpenAiClient` | unchanged shape | Takes a `Codec`, not a `ChatCompletionsCodec`. Builds the URI from `config.uri(codec.path())`. One switch on the credential writes the headers. |
| `Main` | wiring | `clients` picks the codec with a switch on the credential. The auth file is `$CODEX_HOME/auth.json`, and `~/.codex/auth.json` when `CODEX_HOME` is not set, the way the CLI resolves it. |

## Configuration

| Name | Kind | Values | Default |
|---|---|---|---|
| `KONACODE_AUTH` | environment | `key` or `codex` | `key` |

`key` reads `OPENAI_API_KEY`, and a missing key is the error it is today. `codex` reads the auth
file. Any other word prints one line and exits 1.

The defaults follow the mode:

| | `key` | `codex` |
|---|---|---|
| `KONACODE_BASE_URL` | `https://api.openai.com/v1` | `https://chatgpt.com/backend-api/codex` |
| `KONACODE_MODEL` | `gpt-5-mini` | `gpt-5.5` |

`KONACODE_JUDGE_MODEL` defaults to the model, as today. The judge speaks to the same endpoint with
the same credential.

`OPENAI_API_KEY` set beside `KONACODE_AUTH=codex` is not an error. `KONACODE_AUTH` is the explicit
choice, so konacode ignores the key. FOLLOWUP.md §6 said the opposite. A key in a shell profile
must not force the user to unset it, and an explicit word leaves no ambiguity.

## The headers

`OpenAiClient.sendOnce` writes the headers with one switch on the credential:

| Credential | Headers |
|---|---|
| `ApiKey` | `Authorization: Bearer <key>` |
| `CodexToken` | `Authorization: Bearer <access token>`, `ChatGPT-Account-ID: <account id>`, `originator: konacode`, `User-Agent: konacode` |

`Content-Type: application/json` and `Accept: <codec.accept()>` go on every request.

konacode names itself in `originator` and `User-Agent`. It never writes `codex_cli_rs`. If the
server refuses a client that is not Codex, that is the answer of the provider, and konacode stops.
The research names `Authorization`, `ChatGPT-Account-ID`, `Content-Type` and `Accept` as the
minimum probe, and `originator` and `User-Agent` as the next two. konacode sends all six from the
start, so one probe answers the question.

## The Responses codec

**The request.**

| Field | Value |
|---|---|
| `model` | the model |
| `instructions` | the text of the `SystemMessage`. Two system messages join with a blank line. |
| `input` | every other message, as the items below |
| `tools` | `{"type":"function","name":…,"description":…,"parameters":…}` for each spec, when the list is not empty |
| `tool_choice` | `"auto"`, when the list is not empty |
| `store` | `false` |
| `stream` | `true` |

No `include`, no `reasoning`, no `parallel_tool_calls`, no `prompt_cache_key`.

| konacode | Responses item |
|---|---|
| `UserMessage` | `{"type":"message","role":"user","content":[{"type":"input_text","text":…}]}` |
| `AssistantMessage` with text | `{"type":"message","role":"assistant","content":[{"type":"output_text","text":…}]}` |
| each `ToolCall` of an `AssistantMessage` | `{"type":"function_call","call_id":…,"name":…,"arguments":…}` |
| `ToolMessage` | `{"type":"function_call_output","call_id":…,"output":…}` |

An `AssistantMessage` with empty text and tool calls writes the calls only. These shapes come from
`codex-rs/protocol/src/models.rs` at commit `695ab0a`: the enum `ResponseItem` with
`serde(tag = "type", rename_all = "snake_case")`, and the enum `ContentItem`.

**The reply.** The body is the whole SSE stream. `decodeResponse` reads every line that starts
with `data:` as JSON and looks at `type`:

| Event | What the codec does |
|---|---|
| `response.output_item.done`, `item.type == "message"` | appends the `text` of every `output_text` part to the text |
| `response.output_item.done`, `item.type == "function_call"` | adds `ToolCall(call_id, name, arguments)`. A missing `call_id` is an `LlmException`, as in the other codec. |
| `response.failed` | throws an `LlmException` with `response.error.message` |
| `response.completed` | marks the end |
| any other | ignored, the way the CLI ignores it |

A body with no `response.completed` throws "The stream closed before response.completed." A line
that is not JSON throws, as the other codec does, because a reply konacode cannot read carries
no answer.

`decodeUsage` reads `response.usage.input_tokens`, `output_tokens` and `total_tokens` from the
`response.completed` event. It never throws, for the reason the other codec gives: a count is a
diagnostic.

## The auth file

`CodexAuth.read(Path authFile, Instant now)` reads the JSON with Jackson. It needs
`auth_mode == "chatgpt"`, `tokens.access_token` and `tokens.account_id`. It decodes the second
part of the access token with `Base64.getUrlDecoder()` and reads `exp`. No `exp` means no check.

Each refusal is an `IllegalArgumentException` with one line, and the line ends with "Run
`codex login`, then start konacode again.":

- the file does not exist, or cannot be read
- the file is not JSON, or `auth_mode` is not `chatgpt`
- `tokens.access_token` or `tokens.account_id` is missing or blank
- `exp` is at or before `now`

It holds no refresh, no write and no network. It reads the file once, at start. A token that
expires during a session ends the next turn with a `401`.

## Error channels

Nothing new. A wrong configuration prints one line and exits 1, as every property does today. A
`401` during a turn ends the turn through the existing `LlmException` path, and with a
`CodexToken` the message adds the `codex login` sentence. `429`, `502`, `503` and `504` stay
transient. `response.failed` is an `LlmException`: the turn ends, and the model never reads it.

## Rejected alternatives

**Refresh inside konacode.** It reloads the file, posts to `https://auth.openai.com/oauth/token`
with the Codex client id, writes the three tokens and `last_refresh` back into the whole JSON, and
retries once. About 120 lines, a JWT decoder, and a race with a Codex process on the same file.
Deferred until the endpoint is proven to work.

**The live model list.** `GET .../codex/models` gives the default of the day, `gpt-6-astra`
today. It uses Responses Lite, so the plain shape may fail on it. One more request at start, for a
default that may not work.

**A required `KONACODE_MODEL` under `codex`.** konacode then guesses nothing. Rejected because
one working default is what a first run needs.

**`stream: false`.** One JSON body, and the codec of Chat Completions almost fits. Rejected
because no code in the CLI sends it, so nothing proves the endpoint accepts it.

**A second client class.** Rejected because `OpenAiClient` owns the HTTP, the two retry loops
and the status handling, and none of that changes. The codec and the credential are the two
things that change, and each is one type.

## Out of scope

- The refresh of the token.
- The reasoning passthrough. It is the next item, and FOLLOWUP.md names it.
- Responses Lite.
- A `GET .../codex/models` call.
- The WebSocket transport of the CLI.

## Tests

- `ResponsesCodecTest`, with fixtures under `src/test/resources/openai/`: a request with the
  four item kinds and the tools; a request with no tool; an SSE body with a text reply; one with a
  tool call; one with two output items; one with `response.failed`; one with no
  `response.completed`; the usage from `response.completed`; a body that is not SSE. The reply
  fixtures are hand-written from the Codex source, because no real reply exists yet. The first
  real run with `/trace full` records one, and it replaces the hand-written text reply.
- `CodexAuthTest`, with a temporary folder: a good file with a hand-built JWT; a missing file;
  `auth_mode` not `chatgpt`; a missing access token; a missing account id; an expired `exp`; no
  `exp`; a token that is not a JWT.
- `OpenAiConfigTest`: `KONACODE_AUTH` absent, `key`, `codex` and an unknown word; the two
  default base URLs; the two default models; `KONACODE_MODEL` and `KONACODE_BASE_URL` override
  both; `OPENAI_API_KEY` ignored under `codex`; `CODEX_HOME`.
- `OpenAiClientTest`: the headers for each credential; the `Accept` header from the codec; the
  URI from `codec.path()`; the `401` sentence with a `CodexToken` and not with an `ApiKey`.
- `MainTest`: `clients` picks `ChatCompletionsCodec` for an `ApiKey` and `ResponsesCodec` for a
  `CodexToken`.
- `ChatCompletionsCodecTest`: unchanged, plus `path()` and `accept()`.

## Documents

`README.md` gains a section "A ChatGPT subscription" with the four caveats, the `codex login`
step, and `KONACODE_AUTH` in the configuration table. `CLAUDE.md` gains the rows of the components
table and `KONACODE_AUTH` in its table. `FOLLOWUP.md` §6 becomes built, and §1 and §2 name the
reasoning passthrough of the Responses codec as the next item. `CHANGELOG.md` gains the entry
under Unreleased.
