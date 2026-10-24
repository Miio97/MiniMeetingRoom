package client.media;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioPlayer;
import dev.onvoid.webrtc.media.audio.AudioRecorder;
import dev.onvoid.webrtc.media.audio.AudioSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;

/**
 * Local microphone capture and speaker playback checks. Microphone samples are never played back.
 */
public final class AudioDeviceTester implements AutoCloseable {
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("audio-device-test").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicBoolean signalDetected = new AtomicBoolean();
    private AudioRecorder recorder;
    private AudioPlayer player;
    private final AtomicLong sampleCursor = new AtomicLong();

    public CompletableFuture<Void> startMicrophone(AudioDevice microphone, DoubleConsumer level) {
        stopRequested.set(true);
        return submit(
                () -> {
                    stopNow();
                    stopRequested.set(false);
                    capturedFrames.set(0);
                    signalDetected.set(false);
                    try {
                        AudioRecorder audioRecorder = new AudioRecorder();
                        recorder = audioRecorder;
                        AudioDevice selected =
                                microphone != null
                                        ? microphone
                                        : MediaDevices.getDefaultAudioCaptureDevice();
                        if (selected == null)
                            throw new IllegalStateException("MICROPHONE_OPEN_FAILED");
                        audioRecorder.setAudioDevice(selected);
                        audioRecorder.setAudioSink(
                                (data,
                                        nSamples,
                                        bytesPerSample,
                                        channels,
                                        sampleRate,
                                        delay,
                                        drift) -> {
                                    if (!stopRequested.get() && nSamples > 0) {
                                        capturedFrames.incrementAndGet();
                                        double rms =
                                                recordedLevel(
                                                        data, nSamples, bytesPerSample, channels);
                                        if (rms >= 0.008) signalDetected.set(true);
                                        level.accept(rms);
                                    }
                                });
                        audioRecorder.start();
                        return null;
                    } catch (RuntimeException | LinkageError failure) {
                        stopNow();
                        throw failure;
                    }
                });
    }

    public CompletableFuture<Void> playSpeakerTest(AudioDevice speaker) {
        stopRequested.set(true);
        return submit(
                () -> {
                    stopNow();
                    stopRequested.set(false);
                    try {
                        AudioPlayer audioPlayer = new AudioPlayer();
                        player = audioPlayer;
                        AudioSource tone =
                                (data, nSamples, bytesPerSample, channels, sampleRate) -> {
                                    int count =
                                            Math.min(
                                                    nSamples,
                                                    data.length
                                                            / Math.max(
                                                                    bytesPerSample * channels, 1));
                                    long first = sampleCursor.getAndAdd(count);
                                    for (int i = 0; i < count; i++) {
                                        short value =
                                                (short)
                                                        (Math.sin(
                                                                        2
                                                                                * Math.PI
                                                                                * 440
                                                                                * (first + i)
                                                                                / sampleRate)
                                                                * 5000);
                                        for (int channel = 0; channel < channels; channel++) {
                                            int offset = (i * channels + channel) * bytesPerSample;
                                            if (bytesPerSample >= 2 && offset + 1 < data.length) {
                                                data[offset] = (byte) value;
                                                data[offset + 1] = (byte) (value >> 8);
                                            }
                                            for (int extra = 2;
                                                    extra < bytesPerSample
                                                            && offset + extra < data.length;
                                                    extra++) data[offset + extra] = 0;
                                        }
                                    }
                                    return count;
                                };
                        audioPlayer.setAudioDevice(speaker);
                        audioPlayer.setAudioSource(tone);
                        audioPlayer.start();
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (!stopRequested.get() && System.nanoTime() < until) Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException | LinkageError failure) {
                        stopNow();
                        throw failure;
                    } finally {
                        stopNow();
                    }
                    return null;
                });
    }

    public CompletableFuture<Void> stop() {
        stopRequested.set(true);
        return submit(
                () -> {
                    stopNow();
                    return null;
                });
    }

    public long capturedFrames() {
        return capturedFrames.get();
    }

    public boolean signalDetected() {
        return signalDetected.get();
    }

    private void stopNow() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
    }

    static double rms(byte[] data, int bits) {
        if (bits != 16) return 0;
        return rmsBytes(data, data == null ? 0 : data.length);
    }

    static double recordedLevel(byte[] data, int samples, int bytesPerFrame, int channels) {
        // The native AudioTransport reports bytes per interleaved frame: 4 for
        // stereo PCM16, 2 for mono. samples is the number of frames per channel.
        if (samples <= 0 || channels <= 0 || bytesPerFrame != channels * 2) return 0;
        return rmsBytes(data, (int) Math.min(Integer.MAX_VALUE, (long) samples * bytesPerFrame));
    }

    private static double rmsBytes(byte[] data, int bytes) {
        if (data == null || bytes < 2) return 0;
        long sum = 0;
        int samples = Math.min(data.length, bytes) / 2;
        if (samples == 0) return 0;
        for (int i = 0; i < samples * 2; i += 2) {
            short value = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            sum += (long) value * value;
        }
        return Math.min(1, Math.sqrt((double) sum / samples) / 32768.0);
    }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        if (closed.get())
            return CompletableFuture.failedFuture(new IllegalStateException("MEDIA_CLOSED"));
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            worker.execute(
                    () -> {
                        try {
                            result.complete(task.call());
                        } catch (Exception | LinkageError failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new IllegalStateException("MEDIA_CLOSED"));
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopRequested.set(true);
        try {
            worker.execute(this::stopNow);
        } catch (RejectedExecutionException ignored) {
        }
        worker.shutdown();
    }
}
