package server.filetransfer;

import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.Socket;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import server.config.ServerConfig;

/** Separate bounded admission/control and transfer pools keep metadata usable during bulk I/O. */
public final class FileTransferServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(FileTransferServer.class.getName());
    private final ServerSocket listener;
    private final SSLContext tls;
    private final FileTransferService service;
    private final FileMetadataRepository repository;
    private final TransferStorage storage;
    private final TransferConfig config;
    private final FileRetentionService retention;
    private final Duration retentionPeriod;
    private final int connectionsPerMinute;
    private final ThreadPoolExecutor admission = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("file-control-", 0).factory());
    private final ThreadPoolExecutor transfers;
    private final ScheduledExecutorService maintenance = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon().name("file-maintenance").factory());
    private final Map<SSLSocket, Long> sockets = new ConcurrentHashMap<>();
    private final Map<SSLSocket,Socket> transports=new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rates = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile java.util.function.BooleanSupplier cleanupStopped=() -> true;
    public void cleanupTermination(java.util.function.BooleanSupplier stopped) {cleanupStopped=java.util.Objects.requireNonNull(stopped);}
    private Thread acceptThread;

    public FileTransferServer(SSLContext tls, TransferConfig config, FileMetadataRepository repository,
            TransferStorage storage) throws IOException {
        this.config = config; this.storage = storage; this.repository = repository;this.tls=tls;
        retentionPeriod = retentionPeriod(ServerConfig.get("file.transfer.retention-minutes", null),
                ServerConfig.get("file.transfer.retention-days", null));
        connectionsPerMinute = ServerConfig.getInt("file.transfer.connections-per-ip-minute", 600);
        if (connectionsPerMinute < 20 || connectionsPerMinute > 100000) throw new IllegalArgumentException("Invalid connection admission limit");
        retention = new FileRetentionService(repository, storage);
        service = new FileTransferService(repository, storage, config);
        transfers = new ThreadPoolExecutor(config.maxConcurrent(), config.maxConcurrent(), 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("file-transfer-", 0).factory());
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.bind(), config.port()), 32);
        } catch (IOException | RuntimeException e) {
            try { listener.close(); } catch (IOException secondary) { e.addSuppressed(secondary); }
            admission.shutdownNow(); transfers.shutdownNow(); maintenance.shutdownNow(); throw e;
        }
    }
    public int port() { return listener.getLocalPort(); }
    public void roomSessionGate(FileMetadataRepository.RoomSessionGate gate) { repository.roomSessionGate(gate); }
    /** Reference adapter for older isolated harnesses; production selects cleanupBatch with a fence. */
    @Deprecated public void cleanupRoom(long roomId) throws SQLException,IOException {retention.sweepRoom(roomId);}
    public boolean hasPendingRoom(long roomId) throws IOException {return service.roomTransfers().pending(roomId) || retention.pending(roomId);}
    public void cancelRoom(long roomId,String generation) {service.roomTransfers().cancel(roomId,generation);}
    public boolean cancelAndDrained(server.meeting.RoomCleanupStore.Fence fence) {
        cancelRoom(fence.roomId(),fence.generation());return !service.roomTransfers().pending(fence.roomId());
    }
    public java.util.List<FileMetadataRepository.RetiredFile> cleanupBatch(server.meeting.RoomCleanupStore.Fence fence,java.util.List<FileMetadataRepository.RetiredFile> batch) throws SQLException,IOException {
        if(service.roomTransfers().pending(fence.roomId())) throw new IOException("TRANSFER_STILL_ACTIVE");
        return retention.sweepBatch(fence,batch);
    }
    public int activeRoomTransfers() {return service.roomTransfers().size();}
    public void liveRoomClosed(String generation) {
        service.roomTransfers().closeGeneration(generation,task -> {try {maintenance.execute(task);} catch(RejectedExecutionException stopped) { /* close() aborts every socket */ }});
    }
    public synchronized void start() {
        if (closed || acceptThread != null) throw new IllegalStateException("Server cannot be started twice");
        maintenance.scheduleAtFixedRate(this::timeouts, 1, 1, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                service.reconcile(); storage.cleanup();
                // Room content is retired by ImmediateRoomCleanup, including chat and reopen fencing.
            }
            catch (Exception e) { LOG.warning("STORAGE_ERROR cleanup=" + e.getClass().getSimpleName()); }
        }, 60, 60, TimeUnit.SECONDS);
        acceptThread = Thread.ofPlatform().daemon().name("file-accept").start(this::accept);
    }
    /** Minutes take precedence; retain compatibility with an explicitly configured legacy day policy. */
    static Duration retentionPeriod(String minutes, String days) {
        try {
            if (minutes != null) {
                long value = Long.parseLong(minutes.trim());
                if (value < 0 || value > 36500L * 24 * 60) throw new IllegalArgumentException();
                return Duration.ofMinutes(value);
            }
            if (days != null) {
                long value = Long.parseLong(days.trim());
                if (value < 0 || value > 36500) throw new IllegalArgumentException();
                return Duration.ofDays(value);
            }
            return Duration.ofMinutes(20);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid file retention period", invalid);
        }
    }
    private void accept() {
        while (!closed) {
            try {
                Socket transport=listener.accept();SSLSocket socket;
                try {
                    socket=(SSLSocket)tls.getSocketFactory().createSocket(transport,transport.getInetAddress().getHostAddress(),transport.getPort(),true);
                    socket.setUseClientMode(false);socket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
                } catch(IOException|RuntimeException failure) {transport.close();throw failure;}
                transports.put(socket,transport);
                socket.setSoTimeout(config.timeoutMillis());
                sockets.put(socket, System.nanoTime());
                if (closed) { closeSocket(socket); continue; }
                if (!admit(socket.getInetAddress().getHostAddress())) { closeSocket(socket); continue; }
                try { admission.execute(() -> firstRequest(socket)); }
                catch (RejectedExecutionException e) { closeSocket(socket); }
            } catch (SocketException e) {
                if (!closed) LOG.warning("File listener stopped: " + e.getClass().getSimpleName());
                break;
            } catch (IOException e) { if (!closed) LOG.warning("File listener IO error"); }
        }
    }
    private boolean admit(String address) {
        if (rates.size() >= 4096 && !rates.containsKey(address)) return false;
        RateWindow window = rates.computeIfAbsent(address, key -> new RateWindow());
        synchronized (window) {
            long now = System.nanoTime();
            if (now - window.start >= TimeUnit.MINUTES.toNanos(1)) { window.start = now; window.count = 0; }
            return ++window.count <= connectionsPerMinute;
        }
    }
    private void firstRequest(SSLSocket socket) {
        boolean handedOff = false;
        Frame request = null;
        DataOutputStream output = null;
        try {
            socket.startHandshake();
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), config.chunkSize()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), config.chunkSize()));
            request = TransferProtocol.read(input);
            touch(socket);
            if (request.type() == Type.UPLOAD_INIT || request.type() == Type.DOWNLOAD_REQUEST) {
                Frame first = request; DataOutputStream out = output;
                try { transfers.execute(() -> run(socket, first, input, out)); handedOff = true; }
                catch (RejectedExecutionException e) { throw new TransferException("SERVER_BUSY", "Máy chủ đang bận. Hãy thử lại."); }
            } else service.handle(request, input, output, () -> touch(socket));
        } catch (Exception e) { error(request, output, e); }
        finally { if (!handedOff) closeSocket(socket); }
    }
    private void run(SSLSocket socket, Frame request, DataInputStream input, DataOutputStream output) {
        try { service.handle(request, input, output, () -> touch(socket), () -> abortSocket(socket)); }
        catch (Exception e) { error(request, output, e); }
        finally { closeSocket(socket); }
    }
    private static void error(Frame request, DataOutputStream output, Exception failure) {
        String code = failure instanceof TransferException known ? known.code()
                : failure instanceof SQLException ? "DATABASE_UNAVAILABLE"
                : failure instanceof IOException ? "CONNECTION_OR_STORAGE_ERROR" : "INVALID_REQUEST";
        String message = failure instanceof TransferException known ? known.getMessage()
                : failure instanceof SQLException ? "Database chưa sẵn sàng. Có thể thử tiếp tục lượt truyền."
                : "Lượt truyền bị gián đoạn hoặc dữ liệu không hợp lệ. Hãy thử lại.";
        Type type = request == null ? Type.ERROR : request.type() == Type.UPLOAD_INIT ? Type.UPLOAD_FAILED
                : request.type() == Type.DOWNLOAD_REQUEST ? Type.DOWNLOAD_FAILED : Type.ERROR;
        LOG.warning(type + " transferId=" + (request == null ? "unknown" : request.transferId()) + " code=" + code);
        if (output != null) {
            try {
                TransferProtocol.write(output, new Frame(type, request == null ? new UUID(0, 0) : request.transferId(),
                        TransferProtocol.json(Map.of("code", code, "message", message)), new byte[0]));
            } catch (IOException e) { LOG.fine("Failed to deliver transfer error: connection closed"); }
        }
    }
    private void touch(SSLSocket socket) { sockets.computeIfPresent(socket, (key, last) -> System.nanoTime()); }
    private void timeouts() {
        long now = System.nanoTime();
        // SO_TIMEOUT covers reads; this watchdog also aborts blocked writes/queued handshakes.
        sockets.forEach((socket, last) -> { if (now - last > TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis())) abortSocket(socket); });
        rates.entrySet().removeIf(entry -> now - entry.getValue().start > TimeUnit.MINUTES.toNanos(2));
    }
    private void closeSocket(SSLSocket socket) {
        sockets.remove(socket);
        // Close the underlying transport first; JSSE close() can wait behind its writer lock.
        Socket transport=transports.remove(socket);
        try {if(transport!=null) transport.close();socket.close(); }
        catch (IOException e) { LOG.fine("Socket close failed: " + e.getClass().getSimpleName()); }
    }
    private void abortSocket(SSLSocket socket) {
        Socket transport=transports.get(socket);
        if(transport!=null) try {transport.setSoLinger(true,0);transport.close();} catch(IOException ignored) { }
        closeSocket(socket);
    }
    @Override public void close() throws IOException {
        closed = true;
        try { listener.close(); }
        finally {
            admission.shutdownNow(); transfers.shutdownNow(); maintenance.shutdownNow();
            sockets.keySet().forEach(this::abortSocket);
            boolean stopped = false;
            try {
                stopped = admission.awaitTermination(5, TimeUnit.SECONDS)
                        && transfers.awaitTermination(5, TimeUnit.SECONDS)
                        && maintenance.awaitTermination(5, TimeUnit.SECONDS) && cleanupStopped.getAsBoolean();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (stopped) storage.close();
            else {
                // Never unlock the shared storage while an old worker can still commit.
                LOG.warning("Shutdown pending: keeping storage locked until all workers stop");
                Thread.ofPlatform().daemon().name("file-storage-close").start(() -> {
                    try {
                        while (!admission.awaitTermination(1, TimeUnit.SECONDS)
                                || !transfers.awaitTermination(1, TimeUnit.SECONDS)
                                || !maintenance.awaitTermination(1, TimeUnit.SECONDS) || !cleanupStopped.getAsBoolean()) {Thread.sleep(100);}
                        storage.close();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (IOException e) { LOG.warning("STORAGE_ERROR during deferred close"); }
                });
            }
        }
    }
    private static final class RateWindow { volatile long start = System.nanoTime(); int count; }
}
