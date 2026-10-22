"""Isolated local MariaDB check. Hardcoded private named pipe; never Railway/TCP.

Requires a disposable server at MMRProcCheck20261023 with empty data directory.
Creates fixture tables/data there. Does not establish MySQL 8 production support.
"""
from pathlib import Path
import concurrent.futures
import json
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
CLI = [r'C:\xampp\mysql\bin\mysql.exe', '--no-defaults', '--protocol=PIPE',
       '--socket=MMRProcCheck20261023', '--user=root', '--batch', '--raw',
       '--skip-column-names', '--default-character-set=utf8mb4']
DB = 'mmr_proc_test_' + uuid.uuid4().hex[:12]
def sql(body, database=True, error=None):
    result = subprocess.run(CLI + ([DB] if database else []),
        input="SET time_zone='+00:00';\n"+body, text=True, encoding='utf-8',
        capture_output=True, timeout=25)
    if error:
        assert result.returncode and error in result.stderr, (error,result.stderr)
    elif result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()

sql((ROOT/'database/schema.sql').read_text(encoding='utf-8-sig').replace('Mini_Meeting_Room',DB),False)
sql((ROOT/'database/migrations/001-meeting-runtime.sql').read_text(encoding='utf-8-sig'))
sql(Path(__file__).with_name('005-room-operations-v1.sql').read_text(encoding='utf-8'))
sql("""INSERT INTO Nguoi_Dung(Ten_Dang_Nhap,Mat_Khau_Bam,Ten_Hien_Thi)
VALUES('fixture1','unused','Fixture 1'),('fixture2','unused','Fixture 2'),('fixture3','unused','Fixture 3');
INSERT INTO Phien_Dang_Nhap(Ma_Nguoi_Dung,Token_Bam,Het_Han_Luc)
VALUES(1,REPEAT('A',43),CURRENT_TIMESTAMP+INTERVAL 1 DAY),
(2,REPEAT('B',43),CURRENT_TIMESTAMP+INTERVAL 1 DAY),(3,REPEAT('C',43),CURRENT_TIMESTAMP+INTERVAL 1 DAY);""")

def principal(user,request,h='11'):
    return f"{user},{user},REPEAT('{chr(64+user)}',43),'{request}',UNHEX(REPEAT('{h}',32)),'local-test','epoch1'"
def create(request='create-request-0001',code='MMR-AAAAAAAAAAAAAAAA'):
    return f"CALL mmr_create_room_v1({principal(1,request)},'r_AAAAAAAAAAAAAAAAAAAAAA','{code}','Fixture',NULL,2,FALSE,20,TRUE);"
def join(user,request):
    return f"CALL mmr_join_room_v1({principal(user,request)},1,'r_AAAAAAAAAAAAAAAAAAAAAA','p_{chr(64+user)*22}',TRUE,20);"
def payload(output):
    fields=output.split('\t',2);assert fields[0]=='OK';return int(fields[1]),json.loads(fields[2])
checks=[]
def check(name):
    checks.append(name);print('PASS',name)

assert payload(sql(create()))[1]['databaseRoomId']==1;check('create')
assert payload(sql(create()))[0]==1;check('create replay')
sql(create('create-duplicate-01'),error='Duplicate entry')
assert sql('SELECT COUNT(*) FROM Phong_Hop;')=='1'
assert sql("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Request_ID='create-duplicate-01';")=='0';check('failed create transaction rollback')
sql(create().replace("REPEAT('A',43)","REPEAT('a',43)"),error='SESSION_INVALID');check('token hash comparison case sensitive')
sql(create().replace("REPEAT('11',32)","REPEAT('12',32)"),error='REQUEST_ID_REUSED');check('body mismatch')
assert payload(sql(join(1,'host-join-00000001')))[1]['membershipId']==1;check('host join')
assert payload(sql(join(1,'host-join-00000001')))[0]==1;check('join replay')
with concurrent.futures.ThreadPoolExecutor(2) as pool:
    def compete(user):
        try:sql(join(user,f'guest-join-0000000{user}'));return 'OK'
        except AssertionError as e:
            assert 'ROOM_FULL' in str(e);return 'ROOM_FULL'
    results=list(pool.map(compete,[2,3]))
assert sorted(results)==['OK','ROOM_FULL'];check('concurrent last slot')
assert sql("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL;")=='2';check('capacity remains bounded')
winner=int(sql('SELECT Ma_Nguoi_Dung FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung<>1 AND Roi_Phong_Luc IS NULL;'))
guest_member=int(sql(f'SELECT Ma_Thanh_Vien_Phong FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung={winner};'))
leave=f"CALL mmr_exit_room_v1({principal(winner,'guest-exit-0000001')},1,'r_AAAAAAAAAAAAAAAAAAAAAA',{guest_member},'LEAVE',NULL,NULL,NULL);"
assert payload(sql(leave))[0]==0;check('guest leave')
assert payload(sql(leave))[0]==1;check('leave replay')
sql(join(winner,f'guest-join-0000000{winner}'),error='REQUEST_ALREADY_ENDED');check('ended join cannot replay into admission')
sql(join(winner,'bad-generation-01').replace('r_AAAAAAAAAAAAAAAAAAAAAA','r_ZZZZZZZZZZZZZZZZZZZZZZ'),error='STALE_ROOM');check('stale generation')
sql(join(winner,'not-approved-0001').replace('TRUE,20','FALSE,20'),error='ADMISSION_FORBIDDEN');check('admission must be verified')
sql("UPDATE Phong_Hop SET Da_Khoa=TRUE WHERE Ma_Phong=1;")
sql(join(winner,'locked-join-00001'),error='ROOM_LOCKED');check('room lock')
sql("UPDATE Phong_Hop SET Da_Khoa=FALSE WHERE Ma_Phong=1;")
sql(join(winner,'guest-new-join-001'));check('new explicit join after leave')
new_member=int(sql(f'SELECT Ma_Thanh_Vien_Phong FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung={winner} AND Roi_Phong_Luc IS NULL;'))
sql(f"CALL mmr_exit_room_v1({principal(winner,'forbidden-close-1')},1,'r_AAAAAAAAAAAAAAAAAAAAAA',{new_member},'CLOSE',NULL,NULL,NULL);",error='HOST_REQUIRED');check('guest cannot close')
sql(f"CALL mmr_exit_room_v1({principal(1,'host-no-choice-01')},1,'r_AAAAAAAAAAAAAAAAAAAAAA',1,'LEAVE',NULL,NULL,NULL);",error='HOST_DECISION_REQUIRED');check('host decision required')
transfer=f"CALL mmr_exit_room_v1({principal(1,'host-transfer-001')},1,'r_AAAAAAAAAAAAAAAAAAAAAA',1,'TRANSFER',{winner},{winner},REPEAT('{chr(64+winner)}',43));"
sql(transfer)
assert sql('SELECT Ma_Chu_Phong FROM Phong_Hop WHERE Ma_Phong=1;')==str(winner);check('explicit transfer and host leave atomic')
close=f"CALL mmr_exit_room_v1({principal(winner,'host-close-000001')},1,'r_AAAAAAAAAAAAAAAAAAAAAA',{new_member},'CLOSE',NULL,NULL,NULL);"
closed=payload(sql(close))[1]
assert closed['status']=='DA_DONG' and closed['cleanupRequired']==1;check('close and cleanup signal')
assert payload(sql(close))[0]==1;check('close replay')
assert sql('SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL;')=='0';check('close ends all memberships')
sql("UPDATE MMR_Phong_Van_Hanh SET Room_Opaque_ID='r_ZZZZZZZZZZZZZZZZZZZZZZ' WHERE Ma_Phong=1;")
sql(close,error='STALE_ROOM');check('old exit cannot affect reopened generation')
sql(close.replace('r_AAAAAAAAAAAAAAAAAAAAAA','r_ZZZZZZZZZZZZZZZZZZZZZZ'),error='REQUEST_ALREADY_ENDED');check('old exit receipt cannot authorize new generation')
sql("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=CURRENT_TIMESTAMP WHERE Ma_Phien=1;")
sql(create(),error='SESSION_INVALID');check('revoked session cannot replay')
assert sql("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Request_ID='forbidden-close-1';")=='0';check('failed mutation leaves no receipt')
print('TOTAL',len(checks),'checks passed; MariaDB only, not a MySQL benchmark.')
