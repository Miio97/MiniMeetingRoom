package client.UI.util;

/** Shared avatar styling and duration formatting for the production interface. */
public final class DisplayFormat {
    private DisplayFormat() {}

    public static String initials(String name) {
        if (name.equals("Nguyễn Minh Hiếu")) return "NH";
        if (name.isBlank()) return "?";
        String[] parts = name.strip().split("\\s+");
        return parts.length == 1
                ? parts[0].substring(0, 1).toUpperCase()
                : (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1))
                        .toUpperCase();
    }

    public static String avatarColor(String name) {
        String[] colors = {"#6C5CE7", "#268a80", "#9b587d", "#517da6", "#957346", "#568567"};
        return colors[Math.floorMod(name.hashCode(), colors.length)];
    }

    public static String avatarGradient(String name) {
        String[] colors = {
            "#6C5CE7, #8E7CFF",
            "#00B894, #00CEC9",
            "#FF9F43, #FF7675",
            "#FD79A8, #E84393",
            "#0984E3, #74B9FF"
        };
        return "linear-gradient(to bottom right, "
                + colors[Math.floorMod(name.hashCode(), colors.length)]
                + ")";
    }

    public static String duration(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
