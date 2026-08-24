package dev.konacode.tools;

/**
 * What one call to a tool does. The tool states this fact and decides nothing; a
 * {@code ToolPolicy} reads it and decides.
 *
 * <p>A tool that cannot resolve its argument answers the {@code OUTSIDE} value of its kind. A
 * malformed path, a missing key and a link that fails to resolve all mean one thing: konacode does
 * not know where the call goes. "Unknown" therefore needs no value of its own.
 */
public enum Effect {
    READS_INSIDE,
    READS_OUTSIDE,
    WRITES_INSIDE,
    WRITES_OUTSIDE,
    RUNS
}
