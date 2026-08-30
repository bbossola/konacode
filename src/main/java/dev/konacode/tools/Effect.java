package dev.konacode.tools;

/**
 * What one call to a tool does. The tool states this fact and decides nothing; a
 * {@code ToolPolicy} reads it and decides.
 *
 * <p>A tool that cannot resolve its argument answers the {@code OUTSIDE} value of its kind. A
 * malformed path, a missing key and a link that fails to resolve all mean one thing: konacode
 * does not know where the call goes. "Unknown" therefore needs no value of its own. One exception:
 * a tool whose path argument is optional answers for its default, as {@code list_files} does for
 * the root.
 *
 * <p>{@code RUNS} has one tool that answers it: {@code run_command}. A command names no path,
 * so this value has no {@code INSIDE} form and no {@code OUTSIDE} form.
 *
 * <p>{@code NONE} has one tool that answers it: {@code plan}. That call reaches nothing outside
 * the session, so it names no place, and no policy has a question to ask about it.
 */
public enum Effect {
    READS_INSIDE,
    READS_OUTSIDE,
    WRITES_INSIDE,
    WRITES_OUTSIDE,
    RUNS,
    NONE
}
