package dev.konacode.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Every filesystem operation the tools perform. Keeping them here means path handling has one
 * implementation, and gives path confinement a single place to hook in later.
 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static Workspace ofCurrentDirectory() {
        return new Workspace(Path.of(System.getProperty("user.dir")));
    }

    public Path root() {
        return root;
    }

    /**
     * Turns a path as written by the model into an absolute, normalized path. Relative paths
     * resolve against the root; {@code ~} expands to the home directory, which Java does not do
     * on its own.
     */
    public Path resolve(String rawPath) {
        String trimmed = rawPath == null ? "" : rawPath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Path must not be empty.");
        }

        Path path;
        if (trimmed.equals("~")) {
            path = Path.of(System.getProperty("user.home"));
        } else if (trimmed.startsWith("~/")) {
            path = Path.of(System.getProperty("user.home"), trimmed.substring(2));
        } else {
            path = Path.of(trimmed);
        }

        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.normalize();
    }

    /**
     * Reads at most {@code maxBytes} and decodes UTF-8 leniently. Decoding strictly would fail
     * outright whenever the cap lands mid-codepoint, and report a truncated text file as binary.
     */
    public String readUtf8Capped(Path file, int maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(maxBytes);
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Writes via a temporary file and a move, so a failure never leaves a half-written file.
     *
     * <p>The temp file is created with {@link Files#createFile} rather than
     * {@link Files#createTempFile}: the latter deliberately creates at {@code 0600} regardless of
     * umask, and {@link Files#move} carries the temp file's permissions to the destination — which
     * would silently strip the executable bit from every script this tool edits. For the same
     * reason an existing destination's permissions are copied onto the temp file before the move.
     */
    public void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.getParent() == null ? root : file.getParent();
        Files.createDirectories(parent);

        Path temp = createTempSibling(parent);
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            copyPermissions(file, temp);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path createTempSibling(Path parent) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return Files.createFile(parent.resolve(".konacode-" + UUID.randomUUID() + ".tmp"));
            } catch (FileAlreadyExistsException retry) {
                // Astronomically unlikely; loop rather than fail.
            }
        }
        throw new IOException("Could not create a temporary file in " + parent);
    }

    /** Preserves an existing file's mode across the move. No-op for new files and on non-POSIX filesystems. */
    private static void copyPermissions(Path destination, Path temp) throws IOException {
        if (!Files.exists(destination)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(destination));
        } catch (UnsupportedOperationException nonPosixFilesystem) {
            // Windows and similar: there is no POSIX mode to preserve.
        }
    }

    public List<Path> listSorted(Path directory) throws IOException {
        // Locale.ROOT pins the collation rules so listing order does not vary between a
        // developer's machine and CI, which may run under a different default locale.
        Collator collator = Collator.getInstance(Locale.ROOT);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), collator))
                    .toList();
        }
    }
}
