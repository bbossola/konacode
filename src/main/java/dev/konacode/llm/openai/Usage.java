package dev.konacode.llm.openai;

/** The token counts of one reply. */
public record Usage(int prompt, int completion, int total) {
}
