package client.UI;

import client.UI.components.*;
import client.UI.dialogs.*;
import client.media.VideoCallController;
import common.meeting.*;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

class MeetingRoomControlsTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }
    @Test void hostMustSelectASuccessorOrExplicitlyCloseAndCanCancelWithoutADefaultSuccessor() throws Exception {
        FxTestRuntime.call(() -> {
            Stage owner = new Stage(); owner.setScene(new Scene(new VBox(), 1000, 650)); HostLeaveDialog dialog = new HostLeaveDialog(owner, snapshot(), "host");
            try {
                dialog.show(); var pane = dialog.getDialogPane();
                @SuppressWarnings("unchecked") var people = (ComboBox<ParticipantView>) pane.lookup("#host-successor");
                assertNull(people.getValue()); assertEquals(2, people.getItems().size());
                var transfer = (Button) pane.lookup("#host-leave-transfer"); assertTrue(transfer.isDisabled());
                people.setValue(people.getItems().getLast()); transfer.fire();
                assertEquals(new HostLeaveDialog.Choice("chosen", false), dialog.getResult());
                var close = new HostLeaveDialog(owner, snapshot(), "host"); close.show();
                ((Button) close.getDialogPane().lookup("#host-leave-close")).fire(); assertEquals(new HostLeaveDialog.Choice(null, true), close.getResult());
                var cancel = new HostLeaveDialog(owner, snapshot(), "host"); cancel.show(); cancel.close(); assertNull(cancel.getResult());
            } finally { dialog.close(); owner.close(); }
            return null;
        });
    }
    @Test void eyeControlsHideTheWholeToolbarAndRevealFromTheVideoCorner() throws Exception {
        FxTestRuntime.call(() -> {
            var bar = new ControlBar(false, false, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });
            var toolbar = new MeetingToolbar(bar); StackPane video = new StackPane(toolbar.revealControl());
            VBox root = new VBox(video, toolbar); new Scene(root, 1000, 650);
            assertSame(root.lookup("#toggle-meeting-toolbar"), bar.lookup("#toggle-meeting-toolbar"));
            ((Button) root.lookup("#toggle-meeting-toolbar")).fire();
            assertFalse(toolbar.isManaged()); assertFalse(toolbar.content().isVisible()); assertTrue(toolbar.revealControl().isVisible());
            toolbar.revealControl().fire(); assertTrue(toolbar.isManaged()); assertFalse(toolbar.revealControl().isVisible()); return null;
        });
    }
    @Test void waitingQueueIsHostOnlyAndLockedRoomsCannotApproveButCanReject() throws Exception {
        FxTestRuntime.call(() -> {
            var queue = new AdmissionPanel(); new Scene(queue); var waiting = List.of(new Participant(2, "Waiting", false, false, false));
            List<String> actions = new ArrayList<>();
            queue.update(waiting, false, false, (action, id) -> actions.add(action)); assertFalse(queue.isManaged());
            queue.update(waiting, true, true, (action, id) -> actions.add(action)); assertTrue(queue.isVisible());
            queue.applyCss(); queue.layout();
            assertTrue(((Button) queue.lookup("#admit-2")).isDisabled());
            ((Button) queue.lookup("#reject-2")).fire(); assertEquals(List.of("ROOM_PENDING_REJECT"), actions);
            queue.update(List.of(), true, false, (action, id) -> { }); assertTrue(queue.isManaged());
            assertNull(queue.lookup("#admit-2"));
            queue.update(List.of(), false, false, (action, id) -> { }); assertFalse(queue.isManaged());
            queue.update(List.of(), true, false, (action, id) -> { }); assertFalse(queue.getChildren().isEmpty()); return null;
        });
    }
    @Test void settingsSerializeChangesAndRollbackRejectedValuesWithoutASeparateCloseRoomButton() throws Exception {
        var video = new VideoCallController((type, body, id) -> CompletableFuture.completedFuture(new JsonObject()), () -> null, () -> null);
        var reply = new AtomicReference<>(new CompletableFuture<RoomResponse>()); var dialog = new AtomicReference<CameraSettingsDialog>();
        Stage owner = FxTestRuntime.call(() -> { var stage = new Stage(); stage.setScene(new Scene(new VBox(), 1000, 650)); return stage; });
        try {
            FxTestRuntime.call(() -> {
                CameraSettingsDialog settings = new CameraSettingsDialog(owner, video, response(false, false), true, (lock, approval) -> reply.get());
                dialog.set(settings); settings.show();
                var pane = settings.getDialogPane(); ((CheckBox) pane.lookup("#room-locked")).fire();
                assertTrue(((CheckBox) pane.lookup("#room-approval")).isDisabled());
                assertTrue(nodes(pane).filter(Button.class::isInstance).map(Button.class::cast).noneMatch(button -> "Đóng phòng".equals(button.getText())));
                return null;
            });
            reply.get().completeExceptionally(new java.io.IOException("CONTROL_REJECTED"));
            FxTestRuntime.await(() -> !((CheckBox) dialog.get().getDialogPane().lookup("#room-locked")).isDisabled());
            FxTestRuntime.call(() -> {
                var pane = dialog.get().getDialogPane(); assertFalse(((CheckBox) pane.lookup("#room-locked")).isSelected());
                reply.set(new CompletableFuture<>()); ((CheckBox) pane.lookup("#room-approval")).fire(); return null;
            });
            reply.get().complete(response(false, true));
            FxTestRuntime.await(() -> !((CheckBox) dialog.get().getDialogPane().lookup("#room-approval")).isDisabled());
            FxTestRuntime.call(() -> {
                assertTrue(((CheckBox) dialog.get().getDialogPane().lookup("#room-approval")).isSelected());
                dialog.get().roomState(response(false, true), false); assertFalse(dialog.get().getDialogPane().lookup("#room-settings-section").isManaged()); return null;
            });
        } finally {
            FxTestRuntime.call(() -> { if (dialog.get() != null) dialog.get().close(); owner.close(); return null; });
            video.close(); video.closeComplete().get(5, TimeUnit.SECONDS);
        }
    }
    private static java.util.stream.Stream<javafx.scene.Node> nodes(javafx.scene.Node node) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(node), node instanceof javafx.scene.Parent parent
                ? parent.getChildrenUnmodifiable().stream().flatMap(MeetingRoomControlsTest::nodes) : java.util.stream.Stream.empty());
    }
    private static RoomResponse response(boolean locked, boolean approval) {
        return new RoomResponse(true, "OK", "", new RoomView(1, "Room", "MMR-TEST", 4, locked, approval, false), "host", List.of(), List.of(), false);
    }
    private static RoomSnapshot snapshot() {
        MediaState media = new MediaState(false, false, false, false, false, false);
        return new RoomSnapshot("room", "MMR-TEST", "Room", RoomStatus.ACTIVE, false, 4, "host", 1,
                List.of(new ParticipantView("host", "Host", RoomRole.HOST, media), new ParticipantView("early", "Early", RoomRole.MEMBER, media), new ParticipantView("chosen", "Chosen", RoomRole.MEMBER, media)));
    }
}
