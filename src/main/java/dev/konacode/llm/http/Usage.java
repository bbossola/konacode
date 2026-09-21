package dev.konacode.llm.http;

/** The token counts of one reply. */
public record Usage(int prompt, int completion, int total) {
}
