package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscapeWatcherTest {

    @Mock
    NonBlockingReader reader;

    @Test
    void escapeStopsTheTurn() throws IOException {
        when(reader.read(anyLong())).thenReturn(27);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertTrue(cancellation.stopped());
    }

    @Test
    void keepsPollingWhileTheReadExpires() throws IOException {
        when(reader.read(anyLong()))
                .thenReturn(NonBlockingReader.READ_EXPIRED)
                .thenReturn(NonBlockingReader.READ_EXPIRED)
                .thenReturn(27);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertTrue(cancellation.stopped());
    }

    @Test
    void endsOfItsOwnAccordAtEndOfInput() throws IOException {
        when(reader.read(anyLong())).thenReturn(NonBlockingReader.EOF);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertFalse(cancellation.stopped());
    }

    @Test
    void endsWhenItIsNoLongerRunning() throws IOException {
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> false);

        assertFalse(cancellation.stopped());
    }

    @Test
    void aReadFailureEndsTheLoopQuietly() throws IOException {
        when(reader.read(anyLong())).thenThrow(new IOException("the terminal went away"));
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertFalse(cancellation.stopped());
    }
}
