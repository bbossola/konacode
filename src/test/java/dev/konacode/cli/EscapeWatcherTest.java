package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscapeWatcherTest {

    @Mock
    NonBlockingReader reader;

    @Mock
    Terminal terminal;

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

    @Test
    void startEntersRawModeAndKeepsSignals() throws Exception {
        Attributes saved = new Attributes();
        Attributes raw = new Attributes();
        when(terminal.enterRawMode()).thenReturn(saved);
        when(terminal.getAttributes()).thenReturn(raw);
        when(terminal.reader()).thenReturn(reader);
        when(reader.read(anyLong())).thenReturn(NonBlockingReader.READ_EXPIRED);
        EscapeWatcher watcher = new EscapeWatcher(terminal, new Cancellation());

        watcher.start();
        try {
            assertTrue(raw.getLocalFlag(Attributes.LocalFlag.ISIG),
                    "ctrl-C must still end konacode");
            verify(terminal).setAttributes(raw);
        } finally {
            watcher.stop();
        }

        verify(terminal).setAttributes(saved);
    }

    @Test
    void stopWithoutStartDoesNothing() {
        new EscapeWatcher(terminal, new Cancellation()).stop();
    }
}
