package common.meeting;

public record RoomView(long id, String name, String code, int capacity, boolean locked, boolean approval, boolean closed) { }
