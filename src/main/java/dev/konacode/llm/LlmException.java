package dev.konacode.llm;

/** A transport or protocol failure. The model cannot fix a 401, so this never reaches it. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
