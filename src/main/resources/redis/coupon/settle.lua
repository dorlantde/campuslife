local problem=guard(false);if problem then return {problem} end
if not rid(ARGV[3]) or not id(ARGV[4]) or not key(ARGV[5]) or not id(ARGV[7]) or (ARGV[8]~='SUCCEEDED' and ARGV[8]~='FAILED') then return {'INVALID'} end
local at=integer(ARGV[6],9007199254740991);if not at then return {'INVALID'} end
local rf='r:'..ARGV[3];local uf='u:'..ARGV[4];local kf='k:'..ARGV[4]..':'..ARGV[5];local cf='c:'..ARGV[4]
local raw=redis.call('HGET',KEYS[2],rf);local r=nil
if raw then r=record(raw);if not r or r.student~=ARGV[4] or r.key~=ARGV[5] or r.campaign~=ARGV[7] or r.generation~=ARGV[1] then return {'CORRUPT'} end end
local n=counters();local changes={}
local function add(k,v) table.insert(changes,k);table.insert(changes,v) end
if ARGV[8]=='SUCCEEDED' then
 if not id(ARGV[9]) then return {'INVALID'} end
 local proof=redis.call('HGET',KEYS[2],cf)
 if proof and proof~=ARGV[9] then return {'CORRUPT'} end
 if r and r.state=='CLOSED' then return {'CORRUPT'} end
 if r and r.state=='SPENT' then if proof~=ARGV[9] then return {'CORRUPT'} end;return {'ALREADY'} end
 if not r and proof~=ARGV[9] then return {'NEEDS_REBUILD'} end
 if r then if n[3]<1 then return {'CORRUPT'} end;add('#held',tostring(n[3]-1));add('#spent',tostring(n[4]+1))
 else r={campaign=ARGV[7],generation=ARGV[1],id=ARGV[3],student=ARGV[4],key=ARGV[5],at=at} end
 r.state='SPENT';add(uf,'CLAIMED');add(cf,ARGV[9])
else
 if r and r.state=='SPENT' then return {'CORRUPT'} end
 if r and r.state=='CLOSED' then return {'ALREADY'} end
 if r then if n[3]<1 then return {'CORRUPT'} end;add('#held',tostring(n[3]-1));add('#free',tostring(n[2]+1))
 else r={campaign=ARGV[7],generation=ARGV[1],id=ARGV[3],student=ARGV[4],key=ARGV[5],at=at} end
 r.state='CLOSED';if redis.call('HGET',KEYS[2],uf)==ARGV[3] then add(uf,'') end
end
local prior=redis.call('HGET',KEYS[2],kf);if not prior or prior=='' or prior==ARGV[3] then add(kf,ARGV[3]) end
add(rf,cjson.encode(r));redis.call('HSET',KEYS[2],unpack(changes));return {'DONE'}
