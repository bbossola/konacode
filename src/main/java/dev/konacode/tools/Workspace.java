package dev.konacode.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
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

    /** Writes via a temporary file and a move, so a failure never leaves a half-written file. */
    public void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.getParent() == null ? root : file.getParent();
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent, ".konacode", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
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

    public List<Path> listSorted(Path directory) throws IOException {
        Collator collator = Collator.getInstance();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), collator))
                    .toList();
        }
    }
}
