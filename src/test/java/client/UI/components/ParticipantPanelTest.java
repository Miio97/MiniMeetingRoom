package client.UI.components;

import common.meeting.Participant;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

class ParticipantPanelTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void openMenuSurvivesRoomPollingAndDispatchesTheChosenMemberActionOnce() throws Exception {
        var people = FXCollections.observableArrayList(
                new Participant(1, "Host", true, false, false), new Participant(2, "Guest", false, false, false));
        var stage = new java.util.concurrent.atomic.AtomicReference<javafx.stage.Stage>();
        var panel = new java.util.concurrent.atomic.AtomicReference<ParticipantPanel>();
        var popup = new java.util.concurrent.atomic.AtomicReference<client.UI.dialogs.HostMenuPopup>();
        var actions = new java.util.ArrayList<String>();
        try {
            FxTestRuntime.call(() -> {
                var root = new ParticipantPanel(people, 1, () -> { }, (action, target) -> actions.add(action + ":" + target));
                panel.set(root); var window = new javafx.stage.Stage(); stage.set(window);
                window.setScene(new Scene(root, 320, 500)); window.show(); root.applyCss(); root.layout();
                ((Button) root.lookup("#participant-menu-2")).fire();
                popup.set(javafx.stage.Window.getWindows().stream().filter(client.UI.dialogs.HostMenuPopup.class::isInstance)
                        .map(client.UI.dialogs.HostMenuPopup.class::cast).findFirst().orElseThrow());
                assertTrue(popup.get().isShowing()); return null;
            });
            for (int i = 0; i < 4; i++) {
                FxTestRuntime.call(() -> {
                    people.setAll(java.util.List.copyOf(people)); panel.get().applyCss(); panel.get().layout(); return null;
                });
                FxTestRuntime.call(() -> { assertTrue(popup.get().isShowing(), "Room polling must not dismiss the member menu"); return null; });
            }
            FxTestRuntime.call(() -> {
                people.set(1, new Participant(2, "Guest", false, true, true)); panel.get().applyCss(); panel.get().layout();
                assertTrue(popup.get().isShowing(), "Media changes must not close an open menu");
                assertFalse(popup.get().getItems().stream().filter(item -> "Tắt microphone".equals(item.getText())).findFirst().orElseThrow().isDisable());
                assertFalse(popup.get().getItems().stream().filter(item -> "Tắt camera".equals(item.getText())).findFirst().orElseThrow().isDisable());
                popup.get().getItems().stream().filter(item -> "Chuyển chủ phòng".equals(item.getText())).findFirst().orElseThrow().fire();
                assertEquals(java.util.List.of("ROOM_TRANSFER:2"), actions);
                people.set(0, new Participant(1, "Host", false, false, false));
                assertFalse(popup.get().isShowing(), "Losing host permission must dismiss the menu"); return null;
            });
        } finally {
            FxTestRuntime.call(() -> { if (popup.get() != null) popup.get().hide(); if (stage.get() != null) stage.get().close(); return null; });
        }
    }

    @Test void onlyHostSeesOtherMembersMenusAndPendingPreventsAnotherAction() throws Exception {
        FxTestRuntime.call(() -> {
            var people = FXCollections.observableArrayList(
                    new Participant(1, "Host", true, true, true), new Participant(2, "Guest", false, true, true));
            var host = new ParticipantPanel(people, 1, () -> { }, (action, target) -> { });
            new Scene(host, 320, 500); host.applyCss(); host.layout();
            assertNull(host.lookup("#participant-menu-1"));
            assertNotNull(host.lookup("#participant-menu-2"));
            host.pending(true); host.applyCss(); host.layout();
            assertTrue(((Button) host.lookup("#participant-menu-2")).isDisabled());
            assertTrue(((Button) host.lookup("#room-invite")).isDisabled());
            host.pending(false); host.applyCss(); host.layout();
            assertFalse(((Button) host.lookup("#participant-menu-2")).isDisabled());
            var guest = new ParticipantPanel(people, 2, () -> { }, (action, target) -> { });
            new Scene(guest, 320, 500); guest.applyCss(); guest.layout();
            assertNull(guest.lookup("#participant-menu-1")); assertNull(guest.lookup("#participant-menu-2"));
            assertFalse(guest.lookup("#room-invite").isVisible());
            people.set(0, new Participant(1, "Host", false, true, true));
            host.applyCss(); host.layout();
            assertNull(host.lookup("#participant-menu-2"));
            return null;
        });
    }
}
