package server.meeting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.*;
import java.time.*;
import server.auth.ConnectionProvider;
import server.auth.SessionPrincipal;

/** One prepared CALL; no outer transaction or metadata-heavy CallableStatement. */
final class RoomRoutineClient {
    record Reply(boolean replay, JsonObject result) { }
    private final ConnectionProvider connections;
    RoomRoutineClient(ConnectionProvider connections) { this.connections=connections; }
    Reply invoke(String routine, Object... arguments) throws SQLException {
        if (!java.util.Set.of("mmr_create_room_v1","mmr_join_room_v1","mmr_exit_room_v1").contains(routine))
            throw new IllegalArgumentException("Unknown room routine");
        long start=System.nanoTime(), acquired=0;
        boolean success=false;
        try (Connection connection=connections.getConnection()) {
            acquired=System.nanoTime();
            if (!connection.getAutoCommit()) throw new SQLException("ROUTINE_REQUIRES_AUTOCOMMIT");
            String placeholders=String.join(",",java.util.Collections.nCopies(arguments.length,"?"));
            try (PreparedStatement statement=connection.prepareStatement("CALL "+routine+"("+placeholders+")")) {
                statement.setQueryTimeout(10);
                for(int i=0;i<arguments.length;i++) {
                    Object value=arguments[i];
                    if(value instanceof byte[] bytes) statement.setBytes(i+1,bytes);
                    else if(value==null) statement.setNull(i+1,Types.NULL);
                    else statement.setObject(i+1,value);
                }
                Reply reply=null;
                boolean results=statement.execute();
                while(true) {
                    if(results) try(ResultSet rows=statement.getResultSet()) {
                        while(rows.next()) {
                            if(reply!=null || !"OK".equals(rows.getString("code"))) throw new SQLException("INVALID_ROUTINE_RESULT");
                            reply=new Reply(rows.getBoolean("replay"),JsonParser.parseString(rows.getString("result")).getAsJsonObject());
                        }
                    }
                    else if(statement.getUpdateCount()==-1) break;
                    results=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
                if(reply==null) throw new SQLException("MISSING_ROUTINE_RESULT");
                success=true;
                return reply;
            }
        } catch(SQLException failure) {
            // 45000 messages are the fixed business codes emitted by our routines only.
            if("45000".equals(failure.getSQLState()) && failure.getMessage()!=null
                    && failure.getMessage().matches("[A-Z][A-Z0-9_]{1,63}")) throw new RoomException(failure.getMessage());
            throw failure;
        } catch(RuntimeException failure) {
            throw new SQLException("INVALID_ROUTINE_RESULT",failure);
        } finally {
            var timing=new java.util.LinkedHashMap<String,Object>();
            timing.put("action",routine.equals("mmr_create_room_v1")?"CREATE_ROOM":routine.equals("mmr_join_room_v1")?"JOIN_ROOM":"LEAVE_ROOM");
            timing.put("outcome",success?"success":"failed");
            timing.put("queue_ms",((acquired==0?System.nanoTime():acquired)-start)/1e6);
            if(acquired!=0) timing.put("database_ms",(System.nanoTime()-acquired)/1e6);
            common.diagnostics.Diagnostics.current().event("room.routine.completed",timing);
        }
    }
    static RoomRepository.StoredRoom room(JsonObject value) throws SQLException {
        try {
            String time=value.get("createdAt").getAsString().replace(' ','T');
            Instant created=LocalDateTime.parse(time).toInstant(ZoneOffset.UTC);
            String status=value.get("status").getAsString();
            var mapped=switch(status) {case "CHO" -> common.meeting.RoomStatus.READY;case "DANG_HOAT_DONG" -> common.meeting.RoomStatus.ACTIVE;case "DA_DONG" -> common.meeting.RoomStatus.CLOSED;default -> throw new IllegalStateException();};
            return new RoomRepository.StoredRoom(value.get("databaseRoomId").getAsLong(),value.get("roomId").getAsString(),
                value.get("joinCode").getAsString(),value.get("name").getAsString(),value.get("pinHash").isJsonNull()?null:value.get("pinHash").getAsString(),
                value.get("hostUserId").getAsLong(),mapped,value.get("locked").getAsInt()!=0,value.get("capacity").getAsInt(),
                value.get("instanceId").getAsString(),value.get("serverEpoch").getAsString(),created.toString());
        } catch(RuntimeException failure) {throw new SQLException("INVALID_ROUTINE_ROOM",failure);}
    }
}
