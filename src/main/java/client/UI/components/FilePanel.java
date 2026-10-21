package client.UI.components;

import client.UI.SceneManager;
import client.UI.VietnamTime;
import client.filetransfer.FileTransferClient;
import client.filetransfer.FileTransferClient.Cancellation;
import client.filetransfer.FileTransferClient.Progress;
import common.filetransfer.FileMetadata;
import common.filetransfer.TransferException;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

/** Existing file tab, now connected to the room-authorized TLS service. */
public final class FilePanel extends BorderPane implements AutoCloseable {
    private final SceneManager manager;
    private final FileTransferClient client;
    private final String token;
    private final String roomIdentity;
    private final long roomId;
    private final VBox list = new VBox(12);
    private final Label status = Ui.label("Đang lấy danh sách tệp…", "caption");
    private final Map<Long, TransferRow> files = new HashMap<>();
    private final Set<TransferRow> transfers = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> daemon(runnable, "mini-meeting-files"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "mini-meeting-file-events"));
    private final Cancellation eventCancellation = new Cancellation();
    private volatile boolean disposed;
    private long afterId;

    public FilePanel(SceneManager manager) {
        this.manager = manager; client = manager.fileTransferClient(); token = manager.sessionToken(); roomId = manager.serverRoom().id();
        roomIdentity=manager.hasVideoRoom() && manager.sfuSession().snapshot()!=null?manager.sfuSession().snapshot().roomId():null;
        list.setPadding(new Insets(16)); setCenter(Ui.scroll(list));
        Button send = Ui.button("+  Gửi tệp", "primary", this::chooseUpload); send.setMaxWidth(Double.MAX_VALUE);
        VBox bottom = Ui.column(8, send, status); bottom.setPadding(new Insets(12)); setBottom(bottom);
        events.scheduleWithFixedDelay(this::refresh, 0, 3, TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    private void refresh() {
        if (disposed) return;
        try {
            List<FileMetadata> available = client.listFiles(token, roomId, afterId, eventCancellation);
            for (FileMetadata file : available) afterId = Math.max(afterId, file.id());
            fx(() -> { available.forEach(this::addFile); status.setText(files.isEmpty() ? "Chưa có tệp trong phòng." : "Tệp được lưu trên máy chủ · TCP/TLS"); });
        } catch (Exception error) { fx(() -> status.setText("Chưa cập nhật được danh sách: " + message(error))); }
    }
    private void chooseUpload() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Chọn tệp để chia sẻ trong phòng");
        File source = chooser.showOpenDialog(manager.stage()); if (source == null) return;
        TransferRow row = new TransferRow(source.getName()); row.source = source.toPath(); row.uploadId = UUID.randomUUID();
        transfers.add(row); list.getChildren().addFirst(row.view); start(row);
    }
    private void addFile(FileMetadata file) {
        if (file.roomId() != roomId || files.containsKey(file.id())) return;
        TransferRow row = new TransferRow(file.originalName()); row.file = file; row.available();
        files.put(file.id(), row); transfers.add(row); list.getChildren().add(row.view);
    }
    private void chooseDownload(TransferRow row) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Lưu " + row.file.originalName()); chooser.setInitialFileName(row.file.originalName());
        File destination = chooser.showSaveDialog(manager.stage()); if (destination == null) return;
        row.destination = destination.toPath(); row.finished = false; start(row);
    }
    private void start(TransferRow row) {
        if (disposed || row.running) return;
        row.cancellation = new Cancellation(); row.cancelRequested = false; row.pauseRequested = false; row.running = true;
        row.progress.setVisible(true); row.progress.setManaged(true); row.pause.setVisible(true); row.pause.setManaged(true);
        row.pause.setDisable(false); row.cancel.setDisable(false); row.retry.setDisable(false);
        row.retry.setVisible(false); row.retry.setManaged(false); row.cancel.setVisible(true); row.cancel.setManaged(true);
        row.download.setDisable(true); row.state.setText("Đang chuẩn bị…");
        try { workers.execute(() -> transfer(row)); }
        catch (RejectedExecutionException busy) { row.running = false; row.failed("Hàng đợi đã đầy. Vui lòng thử lại."); }
    }
    private void transfer(TransferRow row) {
        try {
            FileMetadata completed = null;
            if (row.source != null) {
                if (row.upload == null) row.upload = client.prepareUpload(roomId, row.source, row.uploadId, row.cancellation, p -> progress(row, p)).inMeeting(roomIdentity);
                completed = client.upload(token, row.upload, row.cancellation, p -> progress(row, p));
            } else client.download(token, row.file, row.destination, row.cancellation, p -> progress(row, p));
            FileMetadata uploaded = completed;
            fx(() -> {
                row.running = false; row.finished = true; row.hideActions(); row.progress.setProgress(1);
                row.state.setText(uploaded != null ? "Gửi thành công · Đã xác minh SHA-256" : "Đã lưu: " + row.destination);
                if (uploaded != null) { row.file = uploaded; addFile(uploaded); }
                else row.download.setDisable(false);
            });
        } catch (Exception error) {
            if (row.cancelRequested) cleanupCancelled(row);
            else fx(() -> { row.running = false; row.failed(row.pauseRequested ? "Đã tạm dừng. Có thể tiếp tục." : message(error)); });
        }
    }
    private void progress(TransferRow row, Progress progress) {
        fx(() -> {
            if (!row.running || row.cancelRequested || row.pauseRequested) return;
            double fraction = progress.total() == 0 ? 1 : Math.min(1, (double) progress.transferred() / progress.total());
            row.progress.setProgress(fraction);
            row.state.setText(progress.status() + String.format(Locale.ROOT, " · %.0f%%", fraction * 100));
            row.detail.setText(bytes(progress.transferred()) + " / " + bytes(progress.total()) + String.format(Locale.ROOT, " · %.2f MB/s", progress.bytesPerSecond() / 1_000_000.0));
        });
    }
    private void stop(TransferRow row, boolean cancel) {
        if (disposed || row.finished) return;
        row.cancelRequested = cancel; row.pauseRequested = !cancel;
        row.state.setText(cancel ? "Đang hủy…" : "Đang tạm dừng…"); row.pause.setDisable(true); row.cancel.setDisable(true); row.retry.setDisable(true);
        if (row.cancellation != null) row.cancellation.cancel();
        if (!row.running && cancel) {
            row.running = true;
            try { workers.execute(() -> cleanupCancelled(row)); }
            catch (RejectedExecutionException busy) { row.running = false; row.failed("Chưa thể hủy. Vui lòng thử lại."); }
        }
    }
    private void cleanupCancelled(TransferRow row) {
        String result = "Đã hủy."; boolean removed = false; FileMetadata completed = null;
        try {
            if (row.source != null) completed = client.cancelUpload(token, row.uploadId);
            else if (row.destination != null) client.deletePartialDownload(row.file, row.destination);
            removed = true;
        } catch (Exception error) { result = "Đã dừng. Chưa dọn được tệp tạm: " + message(error); }
        String message = result; boolean cleaned = removed; FileMetadata alreadyComplete = completed;
        fx(() -> {
            row.running = false; row.cancel.setDisable(false); row.pause.setDisable(false); row.retry.setDisable(false);
            if (cleaned) {
                row.finished = true; row.hideActions(); row.download.setDisable(false);
                row.state.setText(alreadyComplete == null ? message : "Tệp đã gửi thành công trước khi yêu cầu hủy đến máy chủ.");
                if (alreadyComplete != null) addFile(alreadyComplete);
            }
            else { row.failed(message); row.retry.setVisible(false); row.retry.setManaged(false); }
        });
    }
    private void fx(Runnable action) { if (!disposed) Platform.runLater(() -> { if (!disposed) action.run(); }); }
    private static String message(Throwable error) {
        String value = SceneManager.connectionError(error);
        return error instanceof TransferException transfer ? transfer.code() + ": " + value : value;
    }
    private static String bytes(long size) {
        if (size < 1000) return size + " B";
        if (size < 1_000_000) return String.format(Locale.ROOT, "%.1f KB", size / 1000.0);
        if (size < 1_000_000_000) return String.format(Locale.ROOT, "%.1f MB", size / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2f GB", size / 1_000_000_000.0);
    }
    @Override public void close() {
        if (disposed) return; disposed = true; eventCancellation.cancel();
        transfers.forEach(row -> { if (row.cancellation != null) row.cancellation.cancel(); });
        events.shutdownNow(); workers.shutdownNow();
    }
    private final class TransferRow {
        final VBox view;
        final Label state = Ui.label("", "caption"), detail = Ui.label("", "caption");
        final ProgressBar progress = new ProgressBar(0);
        final IconButton download;
        final Button pause, retry, cancel;
        Path source, destination;
        UUID uploadId;
        FileMetadata file;
        FileTransferClient.Upload upload;
        volatile Cancellation cancellation;
        volatile boolean running, pauseRequested, cancelRequested;
        boolean finished;
        TransferRow(String name) {
            Label title = Ui.label(name, "strong"); title.setMaxWidth(190);
            download = new IconButton("arrow", "Tải " + name); download.setVisible(false); download.setManaged(false); download.setOnAction(event -> chooseDownload(this));
            pause = Ui.button("Tạm dừng", "small-button", () -> stop(this, false));
            retry = Ui.button("Tiếp tục", "small-button", () -> start(this));
            cancel = Ui.button("Hủy", "small-button", () -> stop(this, true));
            progress.setMaxWidth(Double.MAX_VALUE);
            view = Ui.column(10, Ui.row(8, IconButton.icon("file"), title, Ui.spacer(), download), detail, progress, state, Ui.row(6, pause, retry, cancel));
            view.getStyleClass().add("file-row"); hideActions();
        }
        void available() {
            detail.setText(bytes(file.sizeBytes()) + " · " + file.senderName() + " · " + VietnamTime.formatFileTime(file.createdAt()));
            download.setVisible(true); download.setManaged(true); progress.setVisible(false); progress.setManaged(false); state.setText("Sẵn sàng tải");
        }
        void hideActions() { for (Button button : List.of(pause, retry, cancel)) { button.setVisible(false); button.setManaged(false); button.setDisable(false); } }
        void failed(String text) {
            state.setText(text); pause.setVisible(false); pause.setManaged(false); pause.setDisable(false);
            retry.setVisible(true); retry.setManaged(true); retry.setDisable(false);
            cancel.setVisible(true); cancel.setManaged(true); cancel.setDisable(false);
        }
    }
}
