# Context

Why konacode is built the way it is, and what was decided before any code was written.

## The premise

A coding agent is not mysterious. It is:

1. Send the whole conversation to the model, along with descriptions of the tools it may call.
2. If the model answers with text, print it. Done.
3. If it answers with a tool call instead, run the tool, append the result to the conversation,
   and go back to 1.

Nobody teaches the model to list a directory before reading a file, or to re-read a file after
a failed edit. That behavior emerges from the loop and the tool descriptions alone. konacode
keeps that premise intact — the loop is still the whole trick — while making the pieces around
it replaceable.

The first cut is three tools (`list_files`, `read_file`, `edit_file`), one provider, and no
persistence. Roughly 1100–1300 lines including tests.

## Decisions taken during design

| Decision | Choice | Reasoning |
|---|---|---|
| Intent | A seed for a real agent, not a demo | The plan is to keep adding to it, so the interesting work was never the agent loop but deciding where the seams go. |
| Wire layer | Hand-rolled `java.net.http` + Jackson | Not an SDK, and explicitly not LangChain4j or Spring AI. A framework would hide the `/v1/chat/completions` format, which is exactly the part worth being able to see. Costs ~100–150 lines of request and response types. |
| Baseline | Java 21 LTS + Maven | Records, sealed interfaces, pattern-matching switch, text blocks for tool descriptions. Maven because `pom.xml` is the least surprising thing for a Java reader at this size. |
| Extension seams | All four: tools, LLM provider, conversation, tool policy | Chosen up front rather than retrofitted. Costs roughly 400 lines over the minimal version. |
| Default tool policy | `AllowAllPolicy` | The interface exists so restrictions can be added later without touching the loop; the default imposes nothing. Confinement is deliberately not on day one. |
| Testing | Tools, agent loop against a fake LLM, codec against fixtures | ~38 tests, entirely offline. |
| `maxIterations` | System property `konacode.maxIterations`, default 8 | Eight is enough for read-read-edit and far too few for anything that plans. |

## Design calls worth remembering

Three places where the obvious implementation is wrong, recorded so nobody "simplifies" them
back:

1. **`read_file` decodes with malformed-input replacement.** The cap is 100 KB. Taking the
   first 100,000 *bytes* and then decoding strict UTF-8 fails outright whenever the cut lands
   mid-codepoint, and the natural error message — "not valid UTF-8 or is binary" — is both
   wrong and unrecoverable for the model. Decode leniently instead.

2. **`edit_file` replaces literally.** `String.replace`, never `replaceAll`. The latter treats
   its arguments as a regex and a replacement template, so a `$` or `\` anywhere in the model's
   `new_str` silently corrupts the edit.

3. **Tool results are typed, not stringly.** A sealed `ToolResult` internally, rendered to the
   `<error> …` string only at the boundary where the model reads it. Signalling failure purely
   by string prefix is fine at 500 lines and painful once a policy layer needs to tell outcomes
   apart.

## Environment

- Repository: `git@github.com:bbossola/konacode.git`, MIT, `main`.
- The default `java` on this machine is **11**. konacode needs 21 — `sdk use java 21.0.2-open`.
  Also available via sdkman: 8, 15, 17, 21, 23. Maven 3.9.3 and Gradle 8.2.1 are on the PATH.
- Default model is `gpt-5-mini`.

## Not doing yet

Streaming, conversation persistence, token budgets, sub-agents, a `run_command` tool, path
confinement, and reasoning support. See [FOLLOWUP.md](FOLLOWUP.md) for the ones that carry a
design consequence.
