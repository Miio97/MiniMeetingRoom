package common.filetransfer;

public record RoomInfo(long id, String code, String name, int capacity, long ownerId,String roomIdentity) {
    public RoomInfo(long id,String code,String name,int capacity,long ownerId) {this(id,code,name,capacity,ownerId,null);}
}
