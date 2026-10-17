package client.UI;

import client.UI.components.BalancedVideoGrid;
import client.UI.components.VideoTile;
import client.UI.screens.MeetingScreen;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import client.meeting.MeetingSession;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.meeting.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies video grid layout and UI pagination when 20 participants are in a room.
 */
class MeetingPaginationTest {
    @BeforeAll static void setupFx() throws Exception {
        FxTestRuntime.start();
    }

    @Test void balancedVideoGridArrangesTwentyTilesWithoutNegativeCoordinates() throws Exception {
        FxTestRuntime.call(() -> {
            Stage stage = new Stage();
            List<VideoTile> tiles = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tiles.add(new VideoTile(new client.UI.mock.MockData.Participant("User " + i, i == 0, false, false, "#336699"), i == 0));
            }
            BalancedVideoGrid grid = new BalancedVideoGrid(tiles);
            Scene scene = new Scene(grid, 1280, 720);
            stage.setScene(scene);
            grid.resizeRelocate(0, 0, 1280, 720);
            grid.layout();

            assertEquals(20, grid.getChildren().size());
            for (Node child : grid.getChildren()) {
                assertTrue(child.getLayoutX() >= 0, "Tile layoutX must be non-negative: " + child.getLayoutX());
                assertTrue(child.getLayoutY() >= 0, "Tile layoutY must be non-negative: " + child.getLayoutY());
                assertTrue(child.getBoundsInParent().getWidth() > 0, "Tile width must be positive");
                assertTrue(child.getBoundsInParent().getHeight() > 0, "Tile height must be positive");
                assertTrue(child.getBoundsInParent().getMaxX() <= 1281.0, "Tile exceeds grid width: " + child.getBoundsInParent().getMaxX());
                assertTrue(child.getBoundsInParent().getMaxY() <= 721.0, "Tile exceeds grid height: " + child.getBoundsInParent().getMaxY());
            }
            stage.close();
            return null;
        });
    }

    @Test void meetingScreenPaginatesTwentyParticipantsAcrossTwoPages() throws Exception {
        FxTestRuntime.call(() -> {
            Stage stage = new Stage();
            SceneManager manager = new SceneManager(stage, new AuthClient(), new FileTransferClient());
            manager.acceptSession(AuthResponse.ok("token", new AuthUser(1, "host", "Host", "host@test.invalid"),
                    "hash", Instant.now().plusSeconds(3600).toString()));

            List<Participant> members = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                members.add(new Participant(i, "User " + i, i == 1, true, false));
            }
            RoomView view = new RoomView(100L, "Phòng 20 người", "MMR-TWENTY01", 20, false, false, false);
            RoomResponse response = new RoomResponse(true, "OK", "Thành công", view, "ticket", members, List.of(), false);
            manager.enterRoom(response, "1234");

            MeetingScreen screen = new MeetingScreen(manager);
            Scene scene = new Scene(screen, 1280, 720);
            stage.setScene(scene);
            screen.resize(1280, 720);
            screen.layout();

            // Find pager label "Trang 1/2 · Người 1–12"
            Label pagerLabel = findLabelMatching(screen, "Trang 1/2");
            assertNotNull(pagerLabel, "Expected pager label on page 1");
            assertTrue(pagerLabel.getText().contains("Người 1–12"), "Expected page 1 range: " + pagerLabel.getText());

            // Click "Sau →" button
            Button nextButton = findButtonByText(screen, "Sau →");
            assertNotNull(nextButton, "Expected Sau → button");
            assertFalse(nextButton.isDisabled(), "Next button should be enabled on page 1");

            nextButton.fire();
            screen.layout();

            // Pager label should now show "Trang 2/2 · Người 13–20"
            Label page2Label = findLabelMatching(screen, "Trang 2/2");
            assertNotNull(page2Label, "Expected pager label on page 2");
            assertTrue(page2Label.getText().contains("Người 13–20"), "Expected page 2 range: " + page2Label.getText());

            // Click "← Trước" button
            Button prevButton = findButtonByText(screen, "← Trước");
            assertNotNull(prevButton, "Expected ← Trước button");
            assertFalse(prevButton.isDisabled(), "Previous button should be enabled on page 2");

            prevButton.fire();
            screen.layout();

            // Returned to page 1
            Label returnedLabel = findLabelMatching(screen, "Trang 1/2");
            assertNotNull(returnedLabel, "Expected returned pager label on page 1");

            stage.close();
            return null;
        });
    }

    @Test void sceneManagerMaxRoomCapacityFollowsServerAndRespectsHardCap() throws Exception {
        FxTestRuntime.call(() -> {
            Stage stage = new Stage();
            SceneManager manager = new SceneManager(stage, new AuthClient(), new FileTransferClient());
            // Without session
            assertEquals(250, manager.maxRoomCapacity());

            // With SFU session
            manager.acceptSession(AuthResponse.ok("tok", new AuthUser(1, "u", "U", "u@invalid"), "h", Instant.now().plusSeconds(3600).toString()));
            MeetingSession session = manager.sfuSession();
            assertNotNull(session);
            assertEquals(20, manager.maxRoomCapacity());
            stage.close();
            return null;
        });
    }

    private static Label findLabelMatching(Node root, String substring) {
        if (root instanceof Label l && l.getText() != null && l.getText().contains(substring)) return l;
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Label found = findLabelMatching(child, substring);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findButtonByText(Node root, String text) {
        if (root instanceof Button b && text.equals(b.getText())) return b;
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButtonByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
