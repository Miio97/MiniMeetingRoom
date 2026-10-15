package client.UI.components;

import client.meeting.MeetingSession;
import client.media.VideoCallController;
import common.meeting.RoomSnapshot;
import java.util.*;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Views borrow media owner; disposal never stops a call during normal navigation. */
public final class VideoCallPane extends VBox {
    private final MeetingSession session;
    private final VideoCallController video;
    private final CameraControls cameraControls;
    private final Label status = Ui.label("", "muted");
    private final StackPane canvas = new StackPane();
    private final List<VideoTile> tiles = new ArrayList<>();
    private final ChangeListener<RoomSnapshot> roomListener = (observable, before, after) -> render(after);
    private boolean disposed;
    public VideoCallPane(MeetingSession session) {
        super(12); this.session = session; video = session.video(); setId("video-call-pane");
        cameraControls = new CameraControls(video, false);
        status.setWrapText(true); status.textProperty().bind(video.statusProperty());
        canvas.setMinHeight(240); canvas.setPrefHeight(360); canvas.setMaxHeight(520);
        getChildren().addAll(cameraControls, status, canvas,
                Ui.label("Chỉ video • Microphone và chia sẻ màn hình sẽ được bổ sung sau khi video ổn định.", "caption"));
        session.roomProperty().addListener(roomListener);
        render(session.snapshot());
    }
    private void render(RoomSnapshot room) {
        if (disposed) return;
        tiles.forEach(VideoTile::dispose); tiles.clear(); canvas.getChildren().clear();
        if (room != null) {
            for (var participant : room.participants()) tiles.add(new VideoTile(participant,
                    participant.participantId().equals(session.selfParticipantId()), video.frames(participant.participantId()).imageProperty()));
            canvas.getChildren().add(new BalancedVideoGrid(tiles));
        }
    }
    public void dispose() {
        disposed = true; session.roomProperty().removeListener(roomListener); cameraControls.dispose();
        status.textProperty().unbind();
        tiles.forEach(VideoTile::dispose); tiles.clear();
    }
}
