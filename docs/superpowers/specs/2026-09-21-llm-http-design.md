# The transport and the providers — design

A reorganisation of `dev.konacode.llm.openai`. No behaviour changes.

## Problem

The Codex provider changed what `OpenAiClient` is. Before, it was the client of one OpenAI wire
format. Now it is an HTTP transport with two retry loops, a `Codec` for the body and a
`Credential` for the headers, and it knows nothing about either. The package `openai` holds that
transport beside the two OpenAI codecs and the two OpenAI credentials, and the package name says
who wrote the wire formats, not who serves them.

Two things follow. A reader cannot tell the provider-neutral part from the OpenAI part by the
folder. And a native Anthropic provider, FOLLOWUP.md §5, would fit inside `OpenAiClient` with one
more credential and one more codec, which is a sentence nobody wants to write.

## Decision

konacode splits the package in two. `dev.konacode.llm.http` holds the transport and the two
contracts it needs. `dev.konacode.llm.openai` holds what OpenAI wrote. The arrows run one way:
`cli -> llm.openai -> llm.http -> llm`, and nothing in `llm.http` names a provider.

The transport is `Client`, and its settings are `ClientConfig`. `Client implements LlmClient`
repeats a word, and that is accepted: the package gives the adjective, and the interface in `llm`
keeps the name `Agent` calls it by.

## The packages

```
dev.konacode.llm                 the provider-neutral model, unchanged
  Message, ToolCall, ToolSpec, LlmClient, LlmException

dev.konacode.llm.http            the transport, and the two contracts it needs
  Client                         was OpenAiClient: HTTP, the two retry loops, the status handling
  ClientConfig                   was OpenAiConfig, with no environment reading
  Codec                          the wire format: path, accept, encode, decode, usage
  Credential                     the headers that prove who konacode is
  Usage
  ReplyValidator
  TransientFailure

dev.konacode.llm.openai          what OpenAI wrote
  ChatCompletionsCodec           implements Codec
  ResponsesCodec                 implements Codec
  ApiKey                         implements Credential
  CodexToken                     implements Credential
  CodexAuth                      reads ~/.codex/auth.json
  OpenAi                         reads KONACODE_AUTH and gives the config and the codec
```

## `Credential` writes its own headers

Today `Credential` is sealed, and `OpenAiClient` holds a switch that writes the headers for each
kind, and a second switch that adds the `codex login` sentence to a 401. In `llm.http` neither
switch can exist, because the kinds live in the provider package. So the credential answers, and
the client asks:

```java
public interface Credential {

    /** The headers that prove who konacode is. */
    Map<String, String> headers();

    /** The sentence one status adds to the error, or an empty string. */
    String hint(int status);
}
```

Both methods are abstract and not a default, so a new credential must answer both. That is the
same force the sealed switch gave: a third kind that says nothing does not compile. The two records
move to `llm.openai` as top-level types, `ApiKey` and `CodexToken`, and each one carries the lines
the switches hold today. `CodexToken.hint(401)` gives `CodexAuth.RUN_LOGIN`, and every other call
gives the empty string.

`Client.sendOnce` writes `credential.headers()` into the builder, in the order the map gives, and
it appends `credential.hint(status)` to the message of a refused request. The test that proves no
token reaches a trace event stays as it is, and it moves with the client.

## `OpenAi` pairs the credential with the codec

`Codec.forCredential` goes. The pairing is a fact about OpenAI, so it lives in the OpenAI package,
beside the reading of `KONACODE_AUTH`:

```java
public final class OpenAi {

    /** The config and the codec for one process. The credential decides both. */
    public record Provider(ClientConfig config, Codec codec) {
    }

    public static Provider fromEnvironment(Map<String, String> environment, Path home);

    static Provider fromEnvironment(Map<String, String> environment, Path home, Instant now);
}
```

`fromEnvironment` holds the switch on `KONACODE_AUTH` that `OpenAiConfig.fromEnvironment` holds
today, with the same three outcomes and the same sentences. It resolves the auth file with
`CodexAuth.file(environment, home)`, so `Main` no longer does. The four defaults, `gpt-5-mini`,
`gpt-5.5` and the two base URLs, move to `OpenAi` as constants. `DEFAULT_TIMEOUT` stays on
`ClientConfig`, because a timeout is a fact about the transport.

`ClientConfig` reads no environment variable. It holds five values, it trims and checks them, and
it gives `forJudge()` and `uri(path)`, as today.

The public convenience constructor `OpenAiClient(config, trace)` goes. Only one test used it, and
`Client` takes its codec in every constructor.

## The wiring in `Main`

```java
OpenAi.Provider provider = OpenAi.fromEnvironment(System.getenv(), Path.of(System.getProperty("user.home")));
...
static Clients clients(OpenAi.Provider provider, HttpClient http, Trace trace) {
    return new Clients(new Client(provider.config(), http, provider.codec(), new NamedTrace("kona", trace)),
            new Client(provider.config().forJudge(), http, provider.codec(), new NamedTrace("judge", trace)));
}
```

`Main` still names the concrete implementations, and only there. The day the Anthropic provider
lands, `Main` gains a second factory, chosen by one environment variable, and `Client` does not
change.

## What does not change

- The loop, the tools, the policy and the interfaces. `agent` imports `LlmClient` from `llm`, and
  that package does not move.
- `Codec`, `Usage`, `ReplyValidator` and `TransientFailure` move without a change, with `git mv`,
  so the history follows them.
- The two codecs move without a change, except the removal of `forCredential`.
- Every test moves with its class. The fixtures under `src/test/resources/openai/` stay where they
  are, because the codecs that read them stay in `openai`.
- Every error sentence stays word for word. `OpenAiConfigTest` becomes `OpenAiTest` for the
  environment reading and `ClientConfigTest` for the checks, and each sentence keeps its test.

## Rejected alternatives

**`HttpLlmClient` and `Transport`.** Both say more than `Client`, and `Transport` pairs with
`Endpoint` rather than `ClientConfig`. Rejected by the author: the package gives the adjective, and
`Client implements LlmClient` is a true sentence.

**Rename the interface to `Llm`.** It reads well in `Agent`, and it touches every class and test
that names the interface. `LlmClient` is not wrong, so the cost buys nothing.

**A `codecs` subpackage inside `openai`.** Two files and a factory behind a boundary. The split
into `http` and `openai` is the boundary that earns its cost, and the codecs sit on the right side
of it.

**Keep `Credential` sealed in `llm.http`.** A sealed interface permits subtypes in its own package
only, so `CodexToken` would live in the transport package and name Codex there. The interface with
two abstract methods keeps the force of the seal and lets each provider own its kinds.

## Tests

No new behaviour, so no new test, except one: `CredentialTest` in `llm.openai` proves the
headers and the hint of each record, because those lines leave the switch that
`OpenAiClientTest` covered. The client test then proves that the headers of any credential reach
the request, with a hand-written `Credential` that gives one header, and no OpenAI type.

The suite holds 756 tests before the change, and it holds 756 plus the credential tests after it.

## Documents

`CLAUDE.md` gains the section `dev.konacode.llm.http` and moves the transport rows into it; the
`openai` section keeps the codecs, the credentials, `CodexAuth` and `OpenAi`. The architecture
rule gains one line: `llm.openai -> llm.http -> llm`. `ARCHITECTURE.md` and `README.md` change
where they name `OpenAiClient`. `FOLLOWUP.md` §5 says that the Anthropic provider is one package
beside `openai`, with one codec and one credential, and no client.
