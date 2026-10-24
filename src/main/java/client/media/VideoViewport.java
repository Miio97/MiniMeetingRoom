package client.media;

import java.util.*;

/** Visible camera indices only; a pinned video or screen leaves five thumbnail slots. */
public final class VideoViewport {
    private VideoViewport() {}

    public record Page(int page, int pages, int primary, List<Integer> cameras) {}

    public static Page page(int count, int pinned, boolean screen, int requested) {
        if (count < 0) throw new IllegalArgumentException("PARTICIPANT_COUNT");
        if (pinned < 0 || pinned >= count) pinned = -1;
        boolean separateMain = screen || pinned >= 0;
        int slots = separateMain ? 5 : 6;
        var eligible = new ArrayList<Integer>();
        for (int index = 0; index < count; index++)
            if (screen || index != pinned) eligible.add(index);
        int pages = Math.max(1, (eligible.size() + slots - 1) / slots),
                page = Math.max(0, Math.min(requested, pages - 1));
        var visible =
                List.copyOf(
                        eligible.subList(
                                Math.min(page * slots, eligible.size()),
                                Math.min((page + 1) * slots, eligible.size())));
        return new Page(
                page,
                pages,
                screen ? -1 : pinned >= 0 ? pinned : visible.isEmpty() ? -1 : visible.getFirst(),
                visible);
    }
}
