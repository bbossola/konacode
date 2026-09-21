# How the Codex CLI uses a ChatGPT subscription

Date: 2026-09-21.

Source: the Rust code of the OpenAI Codex CLI at https://github.com/openai/codex, commit
`695ab0a0b427350bdef49a7e24555893807567f6`, dated 2026-09-21 09:28:24 UTC. Every `path:line`
below is relative to the root of that repository. Every other fact carries a URL.

The code base moved since the names in the brief. The login code lives in `codex-rs/login/`. The
request code lives in `codex-rs/codex-api/` and `codex-rs/core/src/client.rs`. The provider
settings live in `codex-rs/model-provider-info/` and `codex-rs/model-provider/`.

## 1. The layout of `~/.codex/auth.json`

The file is `$CODEX_HOME/auth.json` (`codex-rs/login/src/auth/storage.rs:154-156`). The default
`CODEX_HOME` is `~/.codex` (https://developers.openai.com/codex/auth, section "Credential
storage"). The CLI writes the file with mode `0o600` and pretty JSON
(`codex-rs/login/src/auth/storage.rs:206-224`).

The top-level struct (`codex-rs/login/src/auth/storage.rs:39-65`):

```rust
/// Expected structure for $CODEX_HOME/auth.json.
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq)]
pub struct AuthDotJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth_mode: Option<AuthMode>,

    #[serde(rename = "OPENAI_API_KEY")]
    pub openai_api_key: Option<String>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tokens: Option<TokenData>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_refresh: Option<DateTime<Utc>>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_identity: Option<AgentIdentityStorage>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub personal_access_token: Option<String>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bedrock_api_key: Option<BedrockApiKeyAuth>,

    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bedrock_access_keys: Option<BedrockAccessKeysAuth>,
}
```

The `tokens` struct (`codex-rs/login/src/token_data.rs:10-25`):

```rust
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, Default)]
pub struct TokenData {
    /// Flat info parsed from the JWT in auth.json.
    #[serde(deserialize_with = "deserialize_id_token", serialize_with = "serialize_id_token")]
    pub id_token: IdTokenInfo,
    /// This is a JWT.
    pub access_token: String,
    pub refresh_token: String,
    pub account_id: Option<String>,
}
```

Where each fact lives:

| Fact | JSON path | Source |
|---|---|---|
| Access token | `tokens.access_token` | `codex-rs/login/src/token_data.rs:20` |
| Refresh token | `tokens.refresh_token` | `codex-rs/login/src/token_data.rs:22` |
| Account id | `tokens.account_id` | `codex-rs/login/src/token_data.rs:24` |
| Id token | `tokens.id_token`, a JWT string on disk | `codex-rs/login/src/token_data.rs:200-213` |
| Auth mode | `auth_mode` | `codex-rs/login/src/auth/storage.rs:42-43` |
| Last refresh | `last_refresh`, a chrono `DateTime<Utc>` | `codex-rs/login/src/auth/storage.rs:51-52` |
| API key | `OPENAI_API_KEY` | `codex-rs/login/src/auth/storage.rs:45-46` |

The `id_token` is a string on disk. The CLI parses it into `IdTokenInfo` on read and writes
`raw_jwt` back on save (`codex-rs/login/src/token_data.rs:200-213`). `IdTokenInfo` holds `email`,
`chatgpt_plan_type`, `chatgpt_user_id`, `chatgpt_account_id`, `chatgpt_account_is_fedramp` and
`raw_jwt` (`codex-rs/login/src/token_data.rs:28-42`).

The auth mode is an enum with `#[serde(rename_all = "lowercase")]`
(`codex-rs/protocol/src/auth.rs:7-9`). `ApiKey` serialises as `"apikey"`. `Chatgpt` serialises
as `"chatgpt"`. Six other modes exist: `chatgptAuthTokens`, `headers`, `agentIdentity`,
`personalAccessToken`, `bedrockApiKey` and `bedrockAccessKeys`
(`codex-rs/protocol/src/auth.rs:9-40`). When `auth_mode` is absent, the CLI infers the mode: a
`personal_access_token` field gives `PersonalAccessToken`, a Bedrock field gives a Bedrock mode,
an `OPENAI_API_KEY` field gives `ApiKey`, and nothing gives `Chatgpt`
(`codex-rs/login/src/auth/manager.rs:1744-1761`).

The login flow writes `auth_mode: Some(AuthMode::Chatgpt)`, `last_refresh: Some(Utc::now())`,
and fills `tokens.account_id` from the id token
(`codex-rs/login/src/server.rs:843-864`). The account id comes from the JWT claim
`["https://api.openai.com/auth"]["chatgpt_account_id"]` of the **id token**
(`codex-rs/login/src/server.rs:849-854`, `codex-rs/login/src/success_page.rs:106-125`). The
login flow also exchanges the id token for an API key and stores it in `OPENAI_API_KEY`, when the
exchange succeeds (`codex-rs/login/src/server.rs:441-444`, `codex-rs/login/src/server.rs:1013-1047`).

The CLI also has a keyring store and an in-memory store beside the file
(`codex-rs/login/src/auth/storage.rs:235`, https://developers.openai.com/codex/auth, section
"Credential storage"). konacode needs the file only.

## 2. The endpoint and the headers

### The URL

The base URL for ChatGPT sign-in is a constant
(`codex-rs/model-provider-info/src/lib.rs:77`):

```rust
pub const CHATGPT_CODEX_BASE_URL: &str = "https://chatgpt.com/backend-api/codex";
```

`to_api_provider` selects it for the modes `Chatgpt`, `ChatgptAuthTokens`, `Headers`,
`AgentIdentity` and `PersonalAccessToken`. Every other mode gets `https://api.openai.com/v1`
(`codex-rs/model-provider-info/src/lib.rs:413-430`). A `base_url` in `config.toml` overrides
the choice (`codex-rs/model-provider-info/src/lib.rs:427-430`).

The path is `/responses` (`codex-rs/codex-api/src/endpoint/responses.rs:137-141`). The client
joins the base and the path with one `/`, and appends `query_params` from the provider when they
exist (`codex-rs/codex-api/src/provider.rs:53-75`). The default OpenAI provider has no query
params (`codex-rs/model-provider-info/src/lib.rs:522`). The result is
`https://chatgpt.com/backend-api/codex/responses`. Tests pin that string
(`codex-rs/protocol/src/error_tests.rs:577`, `codex-rs/response-debug-context/src/lib.rs:140`).

### The headers

Three layers add headers. The HTTP client adds its default headers. The provider adds its
headers. The request adds its own. The auth provider adds the credentials last.

Layer 1, the HTTP client (`codex-rs/login/src/auth/default_client.rs:336-348`):

```rust
pub fn default_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert("originator", originator().header_value);
    if let Ok(user_agent) = HeaderValue::from_str(&get_codex_user_agent()) {
        headers.insert(USER_AGENT, user_agent);
    }
    if let Some(requirement) = read_default_client_residency_requirement() { ... }
    headers
}
```

| Header | Value | Source |
|---|---|---|
| `originator` | `codex_cli_rs` by default; `CODEX_INTERNAL_ORIGINATOR_OVERRIDE` overrides it | `codex-rs/login/src/auth/default_client.rs:40-41,62-66` |
| `User-Agent` | `{originator}/{version} ({os type} {os version}; {arch}) {terminal}`, plus an optional suffix in brackets | `codex-rs/login/src/auth/default_client.rs:150-176` |
| `x-openai-internal-codex-residency` | `us`, only when a managed residency requirement is set | `codex-rs/login/src/auth/default_client.rs:342-347`, `codex-rs/model-provider-info/src/lib.rs:38` |

The `/responses` transport is built with `create_client_for_route`, which starts from
`default_http_client_builder()` and so carries these headers
(`codex-rs/core/src/client.rs:1174-1196`, `codex-rs/login/src/auth/default_client.rs:296-299`).

Layer 2, the provider (`codex-rs/model-provider-info/src/lib.rs:512-550`):

| Header | Value | Source |
|---|---|---|
| `version` | `CARGO_PKG_VERSION` of the CLI | `codex-rs/model-provider-info/src/lib.rs:522-526` |
| `OpenAI-Organization` | the env var `OPENAI_ORGANIZATION`, when set | `codex-rs/model-provider-info/src/lib.rs:527-537` |
| `OpenAI-Project` | the env var `OPENAI_PROJECT`, when set | `codex-rs/model-provider-info/src/lib.rs:527-537` |

Layer 3, the request. `build_responses_options` and `stream_request` add these
(`codex-rs/core/src/client.rs:1347-1376`, `codex-rs/codex-api/src/endpoint/responses.rs:86-93`,
`codex-rs/codex-api/src/requests/headers.rs:5-14`):

| Header | Value | Source |
|---|---|---|
| `Accept` | `text/event-stream` | `codex-rs/codex-api/src/endpoint/responses.rs:143-146` |
| `Content-Type` | `application/json`, set by the transport when absent | `codex-rs/http-client/src/request.rs:232-237` |
| `Content-Encoding` | `zstd`, only when request compression is enabled and the auth uses the Codex backend | `codex-rs/core/src/client.rs:1582-1591`, `codex-rs/http-client/src/request.rs:196-217` |
| `session-id` | the prompt cache key, which is the session id for a root agent | `codex-rs/codex-api/src/requests/headers.rs:7-9`, `codex-rs/core/src/client.rs:577-583,561-573` |
| `thread-id` | the thread id | `codex-rs/codex-api/src/requests/headers.rs:10-12` |
| `x-client-request-id` | the thread id | `codex-rs/codex-api/src/endpoint/responses.rs:87-89` |
| `x-codex-window-id` | the window id | `codex-rs/core/src/responses_metadata.rs:356` |
| `x-codex-turn-metadata` | a JSON object about the turn, when present | `codex-rs/core/src/responses_metadata.rs:359-370` |
| `x-codex-turn-state` | a sticky routing token from an earlier response in the turn, when present | `codex-rs/core/src/client.rs:2238-2244` |
| `x-codex-beta-features` | a comma list of beta feature keys, when any | `codex-rs/core/src/client.rs:2232-2237` |
| `x-codex-routing-hint` | a hint the client computes, when any | `codex-rs/core/src/client.rs:1683-1692` |
| `x-openai-subagent` | `review`, `compact` and so on, only for a sub-agent | `codex-rs/codex-api/src/requests/headers.rs:16-31` |
| `x-oai-attestation` | only when attestation is on | `codex-rs/core/src/client.rs:1367-1369` |
| `x-openai-internal-codex-responses-lite` | `true`, only for a model with `use_responses_lite` | `codex-rs/core/src/client.rs:2247-2254` |
| `originator` | again, only when the thread originator differs from the process one | `codex-rs/login/src/auth/default_client.rs:123-137` |

The names `session_id` and `conversation_id` no longer exist as header names in this commit. The
CLI uses `session-id` and `thread-id`. The string `session_id` lives on as a key inside the
`client_metadata` body field (`codex-rs/core/src/responses_metadata.rs:28,315-324`).

Layer 4, the credentials. For the modes `ApiKey`, `Chatgpt`, `ChatgptAuthTokens` and
`PersonalAccessToken`, the CLI builds a `BearerAuthProvider`
(`codex-rs/model-provider/src/auth.rs:316-323`). It sets
(`codex-rs/model-provider/src/bearer_auth_provider.rs:31-47`):

```rust
fn add_auth_headers(&self, headers: &mut HeaderMap) {
    if let Some(token) = self.token.as_ref()
        && let Ok(header) = HeaderValue::from_str(&format!("Bearer {token}"))
    {
        let _ = headers.insert(http::header::AUTHORIZATION, header);
    }
    if let Some(account_id) = self.account_id.as_ref()
        && let Ok(header) = HeaderValue::from_str(account_id)
    {
        let _ = headers.insert("ChatGPT-Account-ID", header);
    }
    if self.is_fedramp_account {
        let _ = headers.insert("X-OpenAI-Fedramp", HeaderValue::from_static("true"));
    }
}
```

| Header | Value | Source |
|---|---|---|
| `Authorization` | `Bearer {tokens.access_token}` | `codex-rs/login/src/auth/manager.rs:578-584` |
| `ChatGPT-Account-ID` | `tokens.account_id`, which the login wrote from the id token claim `https://api.openai.com/auth` → `chatgpt_account_id` | `codex-rs/login/src/auth/manager.rs:599-611`, `codex-rs/login/src/server.rs:849-854` |
| `X-OpenAI-Fedramp` | `true`, only when the id token claim `chatgpt_account_is_fedramp` is true | `codex-rs/login/src/token_data.rs:97-98,187` |

The `http` crate stores header names in lower case, so the wire name is `chatgpt-account-id`.
The tests match on that spelling (`codex-rs/tui/src/analytics/client_tests.rs:159`).

The `OpenAI-Beta` header is **not** on the HTTP request. The CLI sets it only on the WebSocket
handshake, with the value `responses_websockets=2026-02-06`
(`codex-rs/core/src/client.rs:1319-1322,170`).

## 3. Streaming and the response

### The request is always a stream

`build_responses_request` sets `stream: true` with no condition
(`codex-rs/core/src/client.rs:975`). The struct field is a plain `bool` with no
`skip_serializing_if`, so every body carries `"stream": true`
(`codex-rs/codex-api/src/common.rs:271`).

The only HTTP call to `/responses` is `stream_encoded_json_with`, which sets
`Accept: text/event-stream` and hands the body to an SSE reader
(`codex-rs/codex-api/src/endpoint/responses.rs:123-158`). Every other `execute` call in the
`codex-api` crate goes to `/models`, `/images`, `/memories`, `/search` or `/realtime/calls`,
not to `/responses` (`codex-rs/codex-api/src/endpoint/`).

The CLI has a second transport for the same endpoint. It opens a WebSocket at
`wss://chatgpt.com/backend-api/codex/responses` and sends `response.create` frames
(`codex-rs/codex-api/src/endpoint/responses_websocket.rs:391,425`,
`codex-rs/codex-api/src/provider.rs:89-100`). It tries the WebSocket first when the provider
supports it, and falls back to HTTP (`codex-rs/core/src/client.rs:2135-2185,1021-1029`). Every
bundled model has `prefer_websockets: true` (`codex-rs/models-manager/models.json`). Both
transports stream.

Conclusion: the CLI never sends a request with `stream: false` to this endpoint. The code proves
that the endpoint accepts a stream. The code proves nothing about a non-streaming request. The
endpoint may accept one, or it may refuse one. Only a live test answers that.

### The events the CLI reads

The SSE reader parses each `data:` line as JSON with a `type` field
(`codex-rs/codex-api/src/sse/responses.rs:168-184`). `process_responses_event` dispatches on
the type (`codex-rs/codex-api/src/sse/responses.rs:353-557`):

| Event | What the CLI does | Source |
|---|---|---|
| `response.created` | reads `response.id` | `:408-416` |
| `response.output_item.done` | parses `item` as a `ResponseItem`; **this carries the finished tool call and the finished text message** | `:357-364` |
| `response.output_item.added` | parses `item`, for early display | `:516-523` |
| `response.output_text.delta` | text delta | `:365-369` |
| `response.custom_tool_call_input.delta` | delta of a custom tool input | `:370-380` |
| `response.reasoning_summary_text.delta` / `.done` | reasoning summary | `:381-399` |
| `response.reasoning_text.delta` | reasoning text | `:400-407` |
| `response.reasoning_summary_part.added` | summary part | `:524-531` |
| `response.completed` | reads `response.id`, `response.usage`; **ends the stream** | `:490-515`, `:677-683` |
| `response.failed` | reads `response.error.code` and `.message`, maps to an error, then waits for the stream to close | `:417-478` |
| `response.incomplete` | error with `incomplete_details.reason` | `:479-489` |
| `response.function_call_arguments.delta` / `.done`, `response.content_part.*`, `response.in_progress`, `response.output_text.done`, `response.metadata` and more | ignored on purpose | `:532-543` |

The finished tool call arrives as a `response.output_item.done` event whose `item` has
`"type": "function_call"` with `name`, `arguments` and `call_id`. The finished text arrives as a
`response.output_item.done` event whose `item` has `"type": "message"`. The CLI does not need
the `function_call_arguments` deltas.

The stream ends on `response.completed`. When the stream closes without it, the CLI reports
the last `response.failed` error, or "stream closed before response.completed"
(`codex-rs/codex-api/src/sse/responses.rs:603-608`). An idle gap longer than the provider
`stream_idle_timeout` (default 300 000 ms) ends the stream with an error
(`codex-rs/codex-api/src/sse/responses.rs:609-614`, `codex-rs/model-provider-info/src/lib.rs:63`).

The reader does not handle a top-level `error` event by name. An unknown type falls to a
`debug!` log and is dropped (`codex-rs/codex-api/src/sse/responses.rs:547-552`). A
`response.failed` with `error.code` `rate_limit_exceeded` or `slow_down` becomes a retryable
error with a delay parsed from the message (`codex-rs/codex-api/src/sse/responses.rs:464-471,692-720`).

## 4. The request body

The struct (`codex-rs/codex-api/src/common.rs:259-285`):

```rust
#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct ResponsesApiRequest {
    pub model: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub instructions: String,
    pub input: Vec<ResponseItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<ResponsesApiTools>,
    pub tool_choice: String,
    pub parallel_tool_calls: bool,
    pub reasoning: Option<Reasoning>,
    pub store: bool,
    pub stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream_options: Option<StreamOptions>,
    pub include: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub service_tier: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prompt_cache_key: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<TextControls>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_metadata: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub access_programs: Option<AccessPrograms>,
}
```

The values, from `build_responses_request` (`codex-rs/core/src/client.rs:869-985`):

| Field | Value | Source |
|---|---|---|
| `model` | the model slug | `:967` |
| `instructions` | the base instructions text; empty and omitted for a Responses Lite model | `:917-921,885-916` |
| `input` | the conversation as `ResponseItem` entries | `:878,969` |
| `tools` | a JSON array of tool specs; omitted for a Responses Lite model, where the tools go into `input` | `:920,897-904` |
| `tool_choice` | `"auto"`, always | `:971` |
| `parallel_tool_calls` | from the prompt, `false` for Responses Lite | `:972` |
| `reasoning` | `{effort, summary, context}`, each omitted when `None` | `:848-867,973` |
| `store` | `false`, always | `:974` |
| `stream` | `true`, always | `:975` |
| `stream_options` | `{reasoning_summary_delivery}`, only when concurrent summaries are on, the provider is OpenAI and a summary is requested | `:936-941` |
| `include` | `["reasoning.encrypted_content"]`, always | `:942` |
| `service_tier` | from the model, `None` for Bedrock and for the default tier | `:960-965` |
| `prompt_cache_key` | the session id, or an override, or `{source}:{parent_thread_id}` for an internal sub-agent | `:959,561-573` |
| `text` | `{verbosity, format}`, from the model and an output schema | `:943-958` |
| `client_metadata` | a map with `session_id`, `thread_id`, `x-codex-installation-id`, `x-codex-window-id` and more | `:981`, `codex-rs/core/src/responses_metadata.rs:315-345` |
| `access_programs` | set later from the auth, for the cyber access program | `:1697-1700` |

A function tool serialises as `{"type": "function", "name", "description", "strict", "parameters"}`
(`codex-rs/tools/src/tool_spec.rs:20-25`, `codex-rs/tools/src/responses_api.rs:32-42`).

`store: false`, `stream: true`, `tool_choice: "auto"` and `include` do not depend on the auth
mode. The code sets them the same way for `https://api.openai.com/v1` and for the ChatGPT
backend. No comment in the code gives a reason for `store: false`. The only field that changes
with the backend is the request compression: `Content-Encoding: zstd` applies only when the auth
uses the Codex backend and the provider is OpenAI (`codex-rs/core/src/client.rs:1582-1591`).

The base URL is the one thing the auth mode changes (`codex-rs/model-provider-info/src/lib.rs:413-430`).

## 5. The token refresh

### The endpoint and the body

The constants (`codex-rs/login/src/auth/manager.rs:212,1698`):

```rust
const REFRESH_TOKEN_URL: &str = "https://auth.openai.com/oauth/token";
pub const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";
```

`CODEX_REFRESH_TOKEN_URL_OVERRIDE` and `CODEX_APP_SERVER_LOGIN_CLIENT_ID` override the two
(`codex-rs/login/src/auth/manager.rs:214-216,1700-1710`).

The refresh request is a `POST` with `Content-Type: application/json`. The body is a JSON object
with three fields (`codex-rs/login/src/oauth/client.rs:76-89,108-110`):

```json
{"client_id": "app_EMoamEEZ73f0CkXaXp7hrann", "grant_type": "refresh_token", "refresh_token": "..."}
```

The authorization-code exchange at login uses form encoding. The refresh uses JSON. The comment
says so (`codex-rs/login/src/oauth/client.rs:15`, `codex-rs/login/src/auth/manager.rs:1622`).

The login exchange goes to `{issuer}/oauth/token` with `DEFAULT_ISSUER =
"https://auth.openai.com"` (`codex-rs/login/src/server.rs:76,717`).

### The response, and the write-back

The response struct (`codex-rs/login/src/auth/manager.rs:1691-1696`):

```rust
#[derive(Deserialize, Clone)]
struct RefreshResponse {
    id_token: Option<String>,
    access_token: Option<String>,
    refresh_token: Option<String>,
}
```

The response may hold a new refresh token. `persist_tokens` loads `auth.json`, replaces each of
the three tokens that the response holds, sets `last_refresh = Utc::now()` and saves
(`codex-rs/login/src/auth/manager.rs:1584-1607`). So the CLI writes a rotated refresh token
back. The error code `refresh_token_reused` exists in the classifier, so the server does reject a
refresh token that was used once (`codex-rs/login/src/auth/manager.rs:1668`). A test fixture
holds the server message: "Your refresh token has already been used to generate a new access
token. Please try signing in again." (`codex-rs/login/src/auth/util.rs:24-31`). The two facts
together show that the refresh token rotates and that the old one dies.

### When the CLI refreshes

Two triggers exist.

Trigger 1, before a request. `AuthManager::auth()` calls `should_refresh_proactively`
(`codex-rs/login/src/auth/manager.rs:2359-2372`). The rule
(`codex-rs/login/src/auth/manager.rs:2955-2977`):

```rust
if let Some(tokens) = auth_dot_json.tokens.as_ref()
    && let Ok(Some(expires_at)) = parse_jwt_expiration(&tokens.access_token)
{
    return expires_at <= Utc::now() + chrono::Duration::minutes(CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES);
}
let last_refresh = match auth_dot_json.last_refresh { Some(v) => v, None => return false };
last_refresh < Utc::now() - chrono::Duration::days(TOKEN_REFRESH_INTERVAL)
```

The first rule reads the `exp` claim of the **access token** and refreshes when fewer than 5
minutes remain (`CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES = 5`,
`codex-rs/login/src/auth/manager.rs:204`, `codex-rs/login/src/token_data.rs:142-147`). The
second rule applies only when the access token has no `exp` claim: it refreshes when
`last_refresh` is older than 8 days (`TOKEN_REFRESH_INTERVAL = 8`,
`codex-rs/login/src/auth/manager.rs:203`). A failure here logs an error and the request goes on
with the old token (`codex-rs/login/src/auth/manager.rs:2366-2370`).

Trigger 2, after a `401`. `is_recoverable_auth_error` is true for status `401`
(`codex-rs/model-provider/src/provider.rs:194-199`). `stream_responses_api` then calls
`handle_unauthorized` and loops (`codex-rs/core/src/client.rs:1728-1753`). The recovery has two
steps: reload `auth.json` from disk, then refresh from the authority
(`codex-rs/login/src/auth/manager.rs:1957-2012`). The reload step exists because another Codex
process may have refreshed the file already (`codex-rs/login/src/auth/manager.rs:2795-2798`).
When the refresh succeeds, the loop sends the request again.

A lock serialises refreshes inside one process (`codex-rs/login/src/auth/manager.rs:2800`).

### A refresh failure

`request_chatgpt_token_refresh` sorts a failure into two kinds
(`codex-rs/login/src/auth/manager.rs:1611-1658`):

- `Permanent`, when the status is `401`, or the body code is `refresh_token_expired`,
  `refresh_token_reused` or `refresh_token_invalidated`, or the status is `400` with the code
  `invalid_grant`.
- `Transient`, for every other rejected status, for a transport error, and for a body the CLI
  cannot parse.

The code is read from `error`, then `error.code`, then `code` in the JSON body
(`codex-rs/login/src/oauth/error.rs:134-147`).

The messages the user sees for a permanent failure (`codex-rs/login/src/auth/manager.rs:206-211`):

- expired: "Your access token could not be refreshed because your refresh token has expired.
  Please log out and sign in again."
- reused: "Your access token could not be refreshed because your refresh token was already
  used. Please log out and sign in again."
- revoked: "Your access token could not be refreshed because your refresh token was revoked.
  Please log out and sign in again."
- other: "Your access token could not be refreshed. Please log out and sign in again."

A permanent failure becomes `CodexErr::RefreshTokenFailed`, which the protocol maps to the
`Unauthorized` error class (`codex-rs/core/src/client.rs:2618-2640`,
`codex-rs/protocol/src/error.rs:174,469`). The manager remembers the permanent failure for the
same credentials and refuses a second attempt until the auth changes
(`codex-rs/login/src/auth/manager.rs:2851-2856,2885-2889`). A transient failure becomes
`CodexErr::Io` with the status and the body (`codex-rs/core/src/client.rs:2641-2663`).

## 6. The models

### Two sources

Source 1, a bundled list. `codex-rs/models-manager/models.json` is compiled into the binary
with `include_str!` (`codex-rs/models-manager/src/lib.rs:13-16`). It seeds the manager at start
(`codex-rs/models-manager/src/manager.rs:318,721-723`).

Source 2, the backend. The manager fetches `GET {base}/models?client_version={major.minor.patch}`
(`codex-rs/codex-api/src/endpoint/models.rs:33-46`, `codex-rs/models-manager/src/lib.rs:19-26`).
With ChatGPT auth the base is `CHATGPT_CODEX_BASE_URL`, so the URL is
`https://chatgpt.com/backend-api/codex/models?client_version=X.Y.Z`
(`codex-rs/model-provider/src/models_endpoint.rs:108-134`, a test pins it at
`codex-rs/model-provider/src/models_endpoint.rs:457`). The request carries the same
`Authorization` and `ChatGPT-Account-ID` headers as `/responses`
(`codex-rs/model-provider/src/models_endpoint.rs:120-126`). The reply is
`{"models": [...]}` (`codex-rs/protocol/src/openai_models.rs:738-744`). The manager caches it
in `$CODEX_HOME/models_cache.json` for 300 seconds
(`codex-rs/models-manager/src/manager.rs:31-32`). With an API key the CLI also asks the Codex
backend, not `/v1/models`; the comment says "Codex metadata is served by the Codex backend, not
the public /v1/models API" (`codex-rs/model-provider/src/models_endpoint.rs:111-119`).

### The bundled names at this commit

From `codex-rs/models-manager/models.json`, in `priority` order:

| Slug | Display name | Picker | Default reasoning | Plans that list it |
|---|---|---|---|---|
| `gpt-6-astra` | GPT-6-Astra | listed | low | free, go, plus, pro, team, business, enterprise, edu and more |
| `gpt-5.6-sol` | GPT-5.6-Sol | listed | low | same |
| `gpt-5.6-terra` | GPT-5.6-Terra | listed | medium | same |
| `gpt-5.6-luna` | GPT-5.6-Luna | listed | medium | same |
| `gpt-daybreak-blue-latest` | Daybreak Blue | hidden | low | same |
| `gpt-daybreak-red-latest` | Daybreak Red | hidden | medium | same |
| `gpt-5.5` | GPT-5.5 | listed | medium | same |
| `gpt-5.4` | GPT-5.4 | hidden | medium | plus, pro, team, business, enterprise, edu; not free |
| `codex-auto-review` | Codex Auto Review | hidden | medium | plus, pro, team, business, enterprise, edu; not free |

Every entry has `supported_in_api: true` and `prefer_websockets: true`. Each entry carries an
`available_in_plans` list, but no Rust code reads that field at this commit; the backend applies
the plan (a `grep` for `available_in_plans` in `codex-rs/**/*.rs` finds nothing).

### The default model

No constant names the default model. The manager sorts the list by `priority`, keeps every
model when the auth uses the Codex backend, and marks the first picker-visible model as the
default (`codex-rs/models-manager/src/manager.rs:168-181`,
`codex-rs/protocol/src/openai_models.rs:912-931`). With the bundled list at this commit the
default is `gpt-6-astra`. The backend list can change this. The comment says "default is the
highest priority available model" (`codex-rs/protocol/src/openai_models.rs:859`). Three named
constants exist for internal jobs only: `codex-auto-review` for approval review, `gpt-5.6-luna`
for memory extraction and `gpt-5.6-terra` for memory consolidation
(`codex-rs/model-provider/src/provider.rs:124-134`).

Every bundled model except `gpt-5.5` and `gpt-5.4` has `use_responses_lite: true`. For such a
model the CLI moves the instructions and the tools into `input` and sends
`reasoning.context: "all_turns"` (`codex-rs/core/src/client.rs:885-916,861-865`). A client that
sends the plain shape, with `instructions` and `tools` at the top level, must test whether the
backend accepts it for these models. The code does not say.

## 7. What OpenAI says

I read four OpenAI pages. Two returned `403 Forbidden` to both `WebFetch` and
`curl -sL -A 'Mozilla/5.0 ...'`:

- https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan
- https://openai.com/policies/terms-of-use/

Two pages answered. `https://developers.openai.com/codex` redirects with `308` to
`https://learn.chatgpt.com/docs`. The redirect comes from the OpenAI server, so I treat the
target as official. The quotes below are verbatim from the raw HTML.

From https://developers.openai.com/codex/auth (served as https://learn.chatgpt.com/docs/auth):

> Codex supports two ways for a person to sign in when using OpenAI models: Sign in with ChatGPT
> for subscription access. Sign in with an API key for usage-based access.

> The ChatGPT desktop app, Codex CLI, and IDE extension support both sign-in methods for local
> work. Codex cloud requires signing in with ChatGPT.

> Use API key authentication for programmatic Codex CLI workflows, such as CI/CD jobs.

> Access tokens are intended for trusted scripts, schedulers, and private CI runners. For general
> OpenAI API calls, continue to use Platform API keys.

> Codex caches login details locally in a plaintext file at ~/.codex/auth.json or in your
> OS-specific credential store.

> For sign in with ChatGPT sessions, Codex refreshes tokens automatically during use before they
> expire, so active sessions usually continue without requiring another browser login.

> If you use file-based storage, treat ~/.codex/auth.json like a password: it contains access
> tokens. Don't commit it, paste it into tickets, or share it in chat.

From https://developers.openai.com/codex/pricing (served as https://learn.chatgpt.com/docs/pricing):

> ChatGPT Work and Codex are included in your ChatGPT Free, Go, Plus, Pro, Business, Edu, or
> Enterprise plan

The Plus plan card lists: "Codex on the web, in the CLI, in the IDE extension, and on iOS". The
API Key card lists: "Codex in the CLI, SDK, or IDE extension", "Model availability follows the
API models available to your key", "Pay for Codex usage based on API pricing".

> All users may also run extra local chats using an API key, with usage charged at standard API
> rates.

From https://developers.openai.com/codex/enterprise/access-tokens:

> Codex access tokens are ChatGPT workspace credentials scoped to Codex permissions. They
> authenticate trusted non-interactive local workflows, including Codex CLI and app-server-based
> automation, with a ChatGPT workspace identity.

> If a Platform API key works for your automation, keep using API key auth.

From the repository `README.md:70`:

> Run `codex` and select **Sign in with ChatGPT**. We recommend signing into your ChatGPT account
> to use Codex as part of your Plus, Pro, Business, Edu, or Enterprise plan.

What this adds up to. Every official sentence I found names the surfaces that the plan covers:
ChatGPT web, the ChatGPT desktop app, Codex CLI, the IDE extension, iOS, Codex cloud and the
Codex SDK. Every official sentence about programmatic or automated use points to an API key or a
Codex access token. I found no official sentence that permits a tool that is not Codex to use
the ChatGPT subscription. I found no official sentence that forbids it by name. The pages that
most likely hold such a sentence, the help article and the Terms of Use, were not readable from
this machine.

## What konacode must decide

**Streaming.** The CLI sends `stream: true` on every request and reads SSE
(`codex-rs/core/src/client.rs:975`, `codex-rs/codex-api/src/endpoint/responses.rs:143-146`).
No code path sends a non-streaming request to `/responses`. konacode has a blocking
`LlmClient.chat`. Two designs fit. Design A: send `stream: true`, read the SSE to
`response.completed`, collect the `response.output_item.done` items, and return one
`AssistantMessage`. This matches what the CLI does and is proven to work. Design B: send
`stream: false` and parse one JSON body. The code cannot confirm that the endpoint accepts
Design B. A live probe is the only test. Design A is the safe default.

**The refresh token rotates.** The response may hold a new `refresh_token`, and the CLI writes
it to `auth.json` at once (`codex-rs/login/src/auth/manager.rs:1584-1607,1691-1696`). The
server rejects a reused refresh token with `refresh_token_reused`
(`codex-rs/login/src/auth/manager.rs:1668`). Two consequences. First, konacode must write the
new tokens back to `~/.codex/auth.json` after every refresh, or the next Codex run and the next
konacode run both fail. Second, konacode and Codex share one file, so konacode should reload
the file before a refresh, the way the CLI does
(`codex-rs/login/src/auth/manager.rs:2795-2798`). A write must keep every other field of the
file, so konacode must read the whole JSON, change three fields plus `last_refresh`, and write
the whole JSON back.

**When to refresh.** The rule is: the `exp` claim of the access token, minus 5 minutes
(`codex-rs/login/src/auth/manager.rs:2955-2977`). konacode needs a base64url decoder for the JWT
payload and a JSON read of `exp`. A `401` on `/responses` must lead to a reload of the file,
then one refresh, then one more attempt (`codex-rs/login/src/auth/manager.rs:1957-2012`).

**Headers, confirmed as sent.** `Authorization: Bearer {access_token}`,
`ChatGPT-Account-ID: {account_id}`, `originator`, `User-Agent`, `version`, `Accept:
text/event-stream`, `Content-Type: application/json`, `session-id`, `thread-id`,
`x-client-request-id`, `x-codex-window-id`. The code proves that the CLI sends each of these.

**Headers, mandatory versus optional.** The code does not say which headers the server
requires. A header the CLI always sends may still be optional on the server. I can say this
much. `Authorization` is the credential, so it is mandatory. `ChatGPT-Account-ID` names the
workspace that the plan belongs to, and the CLI sends it whenever the token has an account id;
a test named `auth error: missing_authorization_header` shows that the server reports a missing
auth header by name (`codex-rs/protocol/src/error_tests.rs:652`), but no test shows the server
reply for a missing account id. `originator` and `User-Agent` are identification headers; the
CLI lets a host override `originator`, and `is_first_party_originator` is a client-side check
only (`codex-rs/login/src/auth/default_client.rs:139-144`,
`codex-rs/core/src/mcp_skill_dependencies.rs:48`). `session-id`, `thread-id`,
`x-client-request-id` and the `x-codex-*` headers are routing and telemetry; the CLI adds some
of them only under a condition, so the server accepts a request without them. `OpenAI-Beta` is
not on the HTTP request. A minimum probe is: `Authorization`, `ChatGPT-Account-ID`,
`Content-Type`, `Accept`. Add `originator` and `User-Agent` next if the probe fails.

**The body.** `store: false` and `include: ["reasoning.encrypted_content"]` go together: the
server keeps nothing, so the reasoning comes back encrypted for the client to send again. A
client that sends `store: false` must send the encrypted reasoning items back in `input` on the
next turn, or lose the reasoning context. konacode can start with `store: false` and no
`include`, and test whether the server accepts that.

**The model names.** The bundled list at this commit is `gpt-6-astra`, `gpt-5.6-sol`,
`gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5`, `gpt-5.4` and three hidden ones. The default is the
first listed model, `gpt-6-astra`. The list changes with each release, so konacode should not
hard-code it. `GET https://chatgpt.com/backend-api/codex/models?client_version=X.Y.Z` with the
same two auth headers gives the live list.

**The official position.** No official page I could read permits a third-party client. No page
I could read forbids one. The two pages most likely to decide it returned `403`. This is a
question for the user, not for the code.

## Not found

- The help article https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan
  returned `403` to both fetch methods. I could not quote it.
- The Terms of Use at https://openai.com/policies/terms-of-use/ returned `403` to both fetch
  methods. I could not quote the clause on automated or programmatic access.
- No official sentence found that names a third-party tool and the ChatGPT subscription together.
- No code or comment that explains why `store` is `false`.
- No code that reads `available_in_plans` from `models.json`; the plan gate is on the server.
- No code that sends a non-streaming request to `/responses`, so no proof either way about
  whether the endpoint accepts `stream: false`.
- No server-side statement of which headers are mandatory. The CLI code shows what it sends,
  not what the server demands.
- No test or fixture that shows the server reply when `ChatGPT-Account-ID` is absent.
- No `OpenAI-Beta` header on the HTTP `/responses` request. It exists on the WebSocket handshake
  only.
- The header names `session_id` and `conversation_id` from the brief do not exist at this
  commit. The current names are `session-id` and `thread-id`.
