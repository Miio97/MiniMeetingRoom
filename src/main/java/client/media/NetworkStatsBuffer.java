package client.media;

/** One writer; locks cover only small array copies, never native calls or UI. */
public final class NetworkStatsBuffer {
    private final NetworkSample[] samples;
    private int next, size;
    private long generation;

    public NetworkStatsBuffer(int capacity) {
        if (capacity < 1 || capacity > 40) throw new IllegalArgumentException("capacity");
        samples = new NetworkSample[capacity];
    }

    public synchronized boolean add(NetworkSample sample) {
        return add(sample, generation);
    }

    public synchronized boolean add(NetworkSample sample, long expectedGeneration) {
        if (expectedGeneration != generation) return false;
        if (size > 0
                && sample.atNanos()
                        <= samples[(next + samples.length - 1) % samples.length].atNanos())
            return false;
        samples[next] = sample;
        next = (next + 1) % samples.length;
        size = Math.min(size + 1, samples.length);
        return true;
    }

    public synchronized NetworkSample[] snapshot() {
        NetworkSample[] result = new NetworkSample[size];
        for (int i = 0; i < size; i++)
            result[i] = samples[(next - size + i + samples.length) % samples.length];
        return result;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(samples, null);
        next = size = 0;
    }

    public synchronized void reset(long generation) {
        clear();
        this.generation = generation;
    }
}
