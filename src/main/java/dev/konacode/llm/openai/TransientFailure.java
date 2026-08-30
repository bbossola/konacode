package dev.konacode.llm.openai;

import dev.konacode.llm.LlmException;

/**
 * A failure that another attempt may pass: a busy provider, or a request that did not arrive. The
 * transport states this fact, and {@code sendUntilDelivered} decides how many attempts it buys.
 */
final class TransientFailure extends LlmException {

    private final String retryReason;

    /**
     * @param message what the user reads when the budget ends, which carries the answer of the
     *                provider
     * @param retryReason the words konacode writes on a trace line, which carry no payload the
     *                    provider chose
     */
    TransientFailure(String message, String retryReason) {
        super(message);
        this.retryReason = retryReason;
    }

    TransientFailure(String message, String retryReason, Throwable cause) {
        super(message, cause);
        this.retryReason = retryReason;
    }

    String retryReason() {
        return retryReason;
    }
}
