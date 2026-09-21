package dev.konacode.llm.openai;

import java.util.Map;

/**
 * What konacode sends to prove who it is.
 *
 * <p>Both methods are abstract and not a default, so a new credential must answer both. That is
 * the force a sealed switch would give, and it lets each provider own its kinds.
 */
public interface Credential {

    /** The headers that prove who konacode is. */
    Map<String, String> headers();

    /** The sentence one status adds to the error, or an empty string. */
    String hint(int status);
}
