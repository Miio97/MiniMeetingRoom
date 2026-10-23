package client.media;

import java.util.function.Consumer;

/** Fixed 10 ms blocks: signed PCM16 little endian, 48 kHz, stereo. No microphone. */
public interface SystemAudioInput extends AutoCloseable {
    void start(Consumer<byte[]> samples) throws Exception;
    default boolean failed() {return false;}
    @Override void close();
}
