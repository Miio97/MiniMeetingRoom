package common.room;

/** Parameters for room commands. Identity and permissions come from the session token. */
public record RoomRequest(String name, String pin, String identifier, int capacity, int offset, int limit,String roomIdentity) {
    public RoomRequest(String name,String pin,String identifier,int capacity,int offset,int limit) {this(name,pin,identifier,capacity,offset,limit,null);}
    public RoomRequest(String name, String pin, String identifier, int capacity) {
        this(name, pin, identifier, capacity, 0, 20);
    }
    public static RoomRequest page(int offset, int limit) {
        return new RoomRequest(null, null, null, 0, offset, limit);
    }
    public RoomRequest inMeeting(String identity) {return new RoomRequest(name,pin,identifier,capacity,offset,limit,identity);}
    @Override public String toString() { return "RoomRequest[capacity=" + capacity + "]"; }
}
