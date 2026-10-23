package client.filetransfer;

import common.auth.TlsSupport;
import common.filetransfer.FileMetadata;
import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A scripted TLS peer exercises defensive client checks independently from the server implementation. */
@Timeout(30)
class FileTransferClientTest {
    @TempDir static Path certificates;
    @TempDir Path files;
    private static SSLContext serverTls, clientTls;
    private SSLServerSocket listener;
    private ExecutorService worker;
    private Future<?> peer;
    private FileTransferClient client;

    @BeforeAll @Timeout(60) static void tls() throws Exception {
        Path keys = certificates.resolve("client-test.p12"), certificate = certificates.resolve("client-test.cer");
        String password = "client-test-certificate-only";
        keytool("-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", keys.toString(), "-storepass", password, "-keypass", password, "-dname", "CN=localhost",
                "-validity", "2", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt");
        keytool("-exportcert", "-alias", "test", "-keystore", keys.toString(), "-storepass", password, "-rfc", "-file", certificate.toString());
        serverTls = TlsSupport.server(keys, password.toCharArray()); clientTls = TlsSupport.client(certificate);
    }
    @AfterEach void close() throws Exception {
        if (client != null) client.close();
        if (listener != null) listener.close();
        if (worker != null) worker.shutdownNow();
    }
    @Test void rejectsCorruptedDownloadBeforeReplacingExistingDestination() throws Exception {
        byte[] content = "correct content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileMetadata file = metadata(content); Path target = files.resolve("destination.txt"); Files.writeString(target, "preserve existing file");
        serve((request, input, output) -> {
            accept(output, request, file, 0);
            send(output, Type.DOWNLOAD_CHUNK, request, Map.of("offset", 0), "corrupt content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            send(output, Type.DOWNLOAD_COMPLETE, request, file, new byte[0]);
        });
        assertEquals("CHECKSUM_MISMATCH", assertThrows(TransferException.class,
                () -> client.download("session", file, target, new FileTransferClient.Cancellation(), null)).code());
        assertEquals("preserve existing file", Files.readString(target));
        assertTrue(Files.exists(FileTransferClient.partialPath(file, target))); peer.get(5, TimeUnit.SECONDS);
    }
    @Test void resumesVerifiedPrefixAndHashesThroughTheLockedHandleOnWindows() throws Exception {
        byte[] content = new byte[131123]; new Random(31).nextBytes(content); FileMetadata file = metadata(content);
        Path target = files.resolve("tài liệu.bin"); byte[] prefix = Arrays.copyOf(content, 65536);
        Files.write(FileTransferClient.partialPath(file, target), prefix);
        serve((request, input, output) -> {
            assertEquals(Type.DOWNLOAD_REQUEST, request.type()); assertEquals(prefix.length, request.header().get("offset").getAsLong());
            assertEquals(hash(prefix), request.header().get("prefixChecksum").getAsString());
            accept(output, request, file, prefix.length);
            send(output, Type.DOWNLOAD_CHUNK, request, Map.of("offset", prefix.length), Arrays.copyOfRange(content, prefix.length, prefix.length + 65536));
            send(output, Type.DOWNLOAD_CHUNK, request, Map.of("offset", prefix.length + 65536), Arrays.copyOfRange(content, prefix.length + 65536, content.length));
            send(output, Type.DOWNLOAD_COMPLETE, request, file, new byte[0]);
        });
        assertEquals(target, client.download("session", file, target, new FileTransferClient.Cancellation(), null));
        assertArrayEquals(content, Files.readAllBytes(target)); assertFalse(Files.exists(FileTransferClient.partialPath(file, target)));
        peer.get(5, TimeUnit.SECONDS);
    }
    @Test void refusesOutOfOrderChunks() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3}; FileMetadata file = metadata(bytes); Path target = files.resolve("file.bin");
        serve((request, input, output) -> {
            accept(output, request, file, 0); send(output, Type.DOWNLOAD_CHUNK, request, Map.of("offset", 1), bytes);
        });
        assertEquals("INVALID_RESPONSE", assertThrows(TransferException.class,
                () -> client.download("session", file, target, new FileTransferClient.Cancellation(), null)).code());
        assertFalse(Files.exists(target)); assertEquals(0, Files.size(FileTransferClient.partialPath(file, target)));
        peer.get(5, TimeUnit.SECONDS);
    }
    @Test void rejectsServerResumeOffsetOutsideSource() throws Exception {
        Path source = files.resolve("source.exe"); Files.writeString(source, "arbitrary binary allowed");
        serve((request, input, output) -> send(output, Type.UPLOAD_ACCEPT, request, Map.of("offset", 999999, "chunkSize", 65536), new byte[0]));
        FileTransferClient.Cancellation cancellation = new FileTransferClient.Cancellation();
        FileTransferClient.Upload upload = client.prepareUpload(1, source, UUID.randomUUID(), cancellation, null);
        assertEquals("INVALID_RESPONSE", assertThrows(TransferException.class, () -> client.upload("session", upload, cancellation, null)).code());
        peer.get(5, TimeUnit.SECONDS);
    }
    @Test void lateSuccessfulCancellationIsPreserved() throws Exception {
        FileMetadata completed = metadata(new byte[0]);
        serve((request, input, output) -> send(output, Type.UPLOAD_COMPLETE, request, completed, new byte[0]));
        assertEquals(completed, client.cancelUpload("session", UUID.randomUUID())); peer.get(5, TimeUnit.SECONDS);
    }
    @Test void closeBeforeVerificationNeverRenamesPartAndLateCancelKeepsCompletedDownload() throws Exception {
        byte[] content="completed bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);var file=metadata(content);Path target=files.resolve("complete.bin");
        serve((request,input,output) -> {accept(output,request,file,0);send(output,Type.DOWNLOAD_CHUNK,request,Map.of("offset",0),content);send(output,Type.DOWNLOAD_COMPLETE,request,file,new byte[0]);});
        var cancellation=new FileTransferClient.Cancellation();
        assertThrows(java.io.IOException.class,() -> client.download("session",file,target,cancellation,p -> {if(p.status().equals("Đang xác minh SHA-256")) cancellation.cancel();}));
        assertFalse(Files.exists(target));assertTrue(Files.exists(FileTransferClient.partialPath(file,target)));peer.get(5,TimeUnit.SECONDS);close();
        serve((request,input,output) -> {accept(output,request,file,content.length);send(output,Type.DOWNLOAD_COMPLETE,request,file,new byte[0]);});
        var completed=new FileTransferClient.Cancellation();client.download("session",file,target,completed,null);completed.cancel();
        assertArrayEquals(content,Files.readAllBytes(target));peer.get(5,TimeUnit.SECONDS);
    }
    @Test void serverAuthorizationErrorCodeIsPreserved() throws Exception {
        serve((request, input, output) -> send(output, Type.ERROR, request, Map.of("code", "PERMISSION_DENIED", "message", "Không có quyền."), new byte[0]));
        assertEquals("PERMISSION_DENIED", assertThrows(TransferException.class, () -> client.listFiles("session", 1, 0)).code());
        peer.get(5, TimeUnit.SECONDS);
    }
    @Test void closeInterruptsBlockedControlReadAndRejectsNewConnections() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        serve((request, input, output) -> { requested.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); });
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<?> result = caller.submit(() -> client.listFiles("session", 1, 0));
            assertTrue(requested.await(5, TimeUnit.SECONDS)); client.close();
            assertInstanceOf(java.io.IOException.class, assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals("CLOSED", assertThrows(TransferException.class, () -> client.listFiles("session", 1, 0)).code());
        } finally { release.countDown(); }
        peer.get(5, TimeUnit.SECONDS);
    }
    @Test void sourceMutationAndCancellationAreRejectedWithoutConnecting() throws Exception {
        client = new FileTransferClient("localhost", 1, clientTls, 5000); Path source = files.resolve("notes.txt"); Files.writeString(source, "first");
        FileTransferClient.Cancellation cancellation = new FileTransferClient.Cancellation();
        FileTransferClient.Upload upload = client.prepareUpload(1, source, UUID.randomUUID(), cancellation, null);
        Files.writeString(source, "a changed file");
        assertEquals("SOURCE_CHANGED", assertThrows(TransferException.class, () -> client.upload("session", upload, cancellation, null)).code());
        cancellation.cancel();
        assertEquals("CANCELLED", assertThrows(TransferException.class, () -> client.prepareUpload(1, source, UUID.randomUUID(), cancellation, null)).code());
    }
    private void serve(Peer action) throws Exception {
        listener = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        listener.setSoTimeout(10000); worker = Executors.newSingleThreadExecutor();
        peer = worker.submit(() -> {
            try (SSLSocket socket = (SSLSocket) listener.accept()) {
                socket.setSoTimeout(10000); socket.startHandshake();
                DataInputStream input = new DataInputStream(socket.getInputStream()); DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                action.run(TransferProtocol.read(input), input, output);
            }
            return null;
        });
        client = new FileTransferClient("localhost", listener.getLocalPort(), clientTls, 10000);
    }
    private static FileMetadata metadata(byte[] content) throws Exception {
        return new FileMetadata(1, 1, 1, "Người gửi", "file.bin", "application/octet-stream", content.length, hash(content), "2026-10-14T00:00:00Z");
    }
    private static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static void accept(DataOutputStream output, Frame request, FileMetadata file, long offset) throws Exception {
        send(output, Type.DOWNLOAD_ACCEPT, request, Map.of("file", file, "offset", offset, "chunkSize", 65536), new byte[0]);
    }
    private static void send(DataOutputStream output, Type type, Frame request, Object header, byte[] payload) throws Exception {
        TransferProtocol.write(output, new Frame(type, request.transferId(), TransferProtocol.json(header), payload));
    }
    @FunctionalInterface private interface Peer { void run(Frame request, DataInputStream input, DataOutputStream output) throws Exception; }
    private static void keytool(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.isRegularFile(executable)) executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(List.of(arguments));
        Path log = Files.createTempFile(certificates, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("keytool timeout"); }
        assertEquals(0, process.exitValue(), () -> "keytool failed: " + log);
    }
}
