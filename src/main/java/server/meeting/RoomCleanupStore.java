package server.meeting;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import server.auth.ConnectionProvider;
import server.filetransfer.FileMetadataRepository.RetiredFile;

/** MySQL routines in production; explicit JDBC reference implementation for isolated H2 tests. */
public final class RoomCleanupStore {
    public record Fence(long roomId,String instance,String generation) { }
    public static final int FILE_BATCH=100, MESSAGE_BATCH=500;
    private final ConnectionProvider connections;
    private final boolean routines;
    public RoomCleanupStore(ConnectionProvider connections,boolean routines) {this.connections=connections;this.routines=routines;}
    public Fence capture(long room,String instance) throws SQLException {
        try(var c=connection();var s=c.prepareStatement("SELECT p.Trang_Thai,x.Instance_ID,x.Room_Opaque_ID FROM Phong_Hop p LEFT JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong WHERE p.Ma_Phong=?")) {
            s.setLong(1,room);try(var r=s.executeQuery()) {
                if(!r.next() || !"DA_DONG".equals(r.getString(1))) return null;
                if(!instance.equals(r.getString(2))) throw new RoomException("ROOM_CLEANUP_OWNER_REQUIRED");
                return new Fence(room,instance,r.getString(3));
            }
        }
    }
    public List<RetiredFile> batch(Fence fence) throws SQLException {
        if(routines) {
            var result=call("mmr_cleanup_batch_v1",fence,FILE_BATCH);var files=new ArrayList<RetiredFile>();
            for(var value:result.getAsJsonArray("files")) {
                var f=value.getAsJsonObject();files.add(new RetiredFile(f.get("id").getAsLong(),f.get("key").getAsString(),f.get("messageId").getAsLong(),fence.roomId()));
            }
            if(files.size()>FILE_BATCH) throw new SQLException("INVALID_CLEANUP_RESULT");return List.copyOf(files);
        }
        try(var c=connection()) {
            begin(c);try {
                validate(c,fence);var files=new ArrayList<RetiredFile>();
                try(var s=c.prepareStatement("SELECT f.Ma_Tep,f.Duong_Dan_Luu_Tru,t.Ma_Tin_Nhan FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan=f.Ma_Tin_Nhan WHERE t.Ma_Phong=? ORDER BY f.Ma_Tep LIMIT 100")) {
                    s.setLong(1,fence.roomId());try(var r=s.executeQuery()) {while(r.next()) files.add(new RetiredFile(r.getLong(1),r.getString(2),r.getLong(3),fence.roomId()));}
                }
                c.commit();return List.copyOf(files);
            } catch(SQLException|RuntimeException e) {c.rollback();throw e;}
        }
    }
    /** Only acknowledge keys whose deletion succeeded. Rollback never claims to restore bytes. */
    public boolean commit(Fence fence,List<RetiredFile> deleted) throws SQLException {
        if(deleted.size()>FILE_BATCH || deleted.stream().anyMatch(f -> f.roomId()!=fence.roomId())) throw new SQLException("INVALID_CLEANUP_BATCH");
        if(routines) {
            var ids=new JsonArray();for(var f:deleted) {var v=new JsonObject();v.addProperty("id",f.fileId());v.addProperty("key",f.storageKey());v.addProperty("messageId",f.messageId());ids.add(v);}
            var r=call("mmr_cleanup_commit_v1",fence,ids.toString(),MESSAGE_BATCH);
            metric("room.cleanup.batch",fence,Map.of("file_count",deleted.size(),"message_count",r.get("messages").getAsInt()));
            return r.get("empty").getAsInt()!=0;
        }
        try(var c=connection()) {
            begin(c);try {
                validate(c,fence);
                if(!deleted.isEmpty()) {
                    // One statement for the whole bounded acknowledgement, exact immutable key + message + room.
                    String predicates=String.join(" OR ",Collections.nCopies(deleted.size(),"(f.Ma_Tep=? AND f.Ma_Tin_Nhan=? AND f.Duong_Dan_Luu_Tru=?)"));
                    try(var s=c.prepareStatement("DELETE FROM Tep_Dinh_Kem f WHERE EXISTS(SELECT 1 FROM Tin_Nhan t WHERE t.Ma_Tin_Nhan=f.Ma_Tin_Nhan AND t.Ma_Phong=?) AND ("+predicates+")")) {
                        int i=1;s.setLong(i++,fence.roomId());for(var f:deleted) {s.setLong(i++,f.fileId());s.setLong(i++,f.messageId());s.setString(i++,f.storageKey());}s.executeUpdate();
                    }
                }
                int messages;
                try(var s=c.prepareStatement("DELETE FROM Tin_Nhan WHERE Ma_Tin_Nhan IN (SELECT id FROM (SELECT t.Ma_Tin_Nhan AS id FROM Tin_Nhan t WHERE t.Ma_Phong=? AND NOT EXISTS(SELECT 1 FROM Tep_Dinh_Kem f WHERE f.Ma_Tin_Nhan=t.Ma_Tin_Nhan) ORDER BY t.Ma_Tin_Nhan LIMIT 500) ids) AND Ma_Phong=?")) {
                    s.setLong(1,fence.roomId());s.setLong(2,fence.roomId());messages=s.executeUpdate();
                }
                boolean empty;try(var s=c.prepareStatement("SELECT 1 FROM Tin_Nhan WHERE Ma_Phong=? LIMIT 1")) {s.setLong(1,fence.roomId());try(var r=s.executeQuery()) {empty=!r.next();}}
                c.commit();metric("room.cleanup.batch",fence,Map.of("file_count",deleted.size(),"message_count",messages));return empty;
            } catch(SQLException|RuntimeException e) {c.rollback();throw e;}
        }
    }
    private static void begin(Connection c) throws SQLException {c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);}
    private static void validate(Connection c,Fence f) throws SQLException {
        try(var s=c.prepareStatement("SELECT p.Trang_Thai,x.Instance_ID,x.Room_Opaque_ID FROM Phong_Hop p JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong WHERE p.Ma_Phong=? FOR UPDATE")) {
            s.setLong(1,f.roomId());try(var r=s.executeQuery()) {
                if(!r.next() || !"DA_DONG".equals(r.getString(1)) || !f.instance().equals(r.getString(2)) || !f.generation().equals(r.getString(3))) throw new RoomException("STALE_CLEANUP");
            }
        }
    }
    private JsonObject call(String name,Fence f,Object... args) throws SQLException {
        long started=System.nanoTime();
        try(var c=connection();var s=c.prepareStatement("CALL "+name+"("+String.join(",",Collections.nCopies(3+args.length,"?"))+")")) {
            if(!c.getAutoCommit()) throw new SQLException("CLEANUP_REQUIRES_AUTOCOMMIT");
            s.setQueryTimeout(10);s.setLong(1,f.roomId());s.setString(2,f.instance());s.setString(3,f.generation());
            for(int i=0;i<args.length;i++) s.setObject(i+4,args[i]);
            boolean more=s.execute();JsonObject result=null;
            while(true) {
                if(more) try(var r=s.getResultSet()) {while(r.next()) {if(result!=null) throw new SQLException("INVALID_CLEANUP_RESULT");result=JsonParser.parseString(r.getString("result")).getAsJsonObject();}}
                else if(s.getUpdateCount()==-1) break;
                more=s.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
            if(result==null) throw new SQLException("MISSING_CLEANUP_RESULT");return result;
        } catch(SQLException e) {
            if(e.getErrorCode()==1305 || "42000".equals(e.getSQLState()) && e.getMessage()!=null && e.getMessage().contains("does not exist")) throw new SQLException("CLEANUP_PROCEDURE_MISSING","42000",1305);
            if("45000".equals(e.getSQLState()) && e.getMessage()!=null && e.getMessage().matches("[A-Z_]{2,64}")) throw new RoomException(e.getMessage());
            throw e;
        } catch(RuntimeException e) {throw new SQLException("INVALID_CLEANUP_RESULT",e);}
        finally {metric("room.cleanup.sql",f,Map.of("database_ms",(System.nanoTime()-started)/1e6,"db_calls",1));}
    }
    private Connection connection() throws SQLException {
        long at=System.nanoTime();try {return connections.getConnection();}
        finally {common.diagnostics.Diagnostics.current().event("room.cleanup.pool",Map.of("queue_ms",(System.nanoTime()-at)/1e6));}
    }
    static void metric(String event,Fence f,Map<String,Object> values) {
        var fields=new HashMap<String,Object>(values);fields.put("room_id",f.roomId());fields.put("generation_hash",f.generation().hashCode());common.diagnostics.Diagnostics.current().event(event,fields);
    }
}
