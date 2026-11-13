local problem=guard(false);if problem then return {problem} end
if redis.call('HGET',KEYS[1],'phase')~='OPEN' then return {'CLOSED'} end
if not rid(ARGV[3]) or not id(ARGV[4]) or not key(ARGV[5]) or not id(ARGV[7]) then return {'INVALID'} end
local at=integer(ARGV[6],9007199254740991);if not at then return {'INVALID'} end
local rf='r:'..ARGV[3];local uf='u:'..ARGV[4];local kf='k:'..ARGV[4]..':'..ARGV[5]
local existing=redis.call('HGET',KEYS[2],rf)
if existing then
 local r=record(existing);if not r or r.student~=ARGV[4] or r.key~=ARGV[5] or r.campaign~=ARGV[7] or r.generation~=ARGV[1] then return {'CORRUPT'} end
 return {r.state,r.id}
end
local prior=redis.call('HGET',KEYS[2],kf)
if prior and prior~='' then
 local r=record(redis.call('HGET',KEYS[2],'r:'..prior))
 if not r or r.student~=ARGV[4] or r.key~=ARGV[5] or r.campaign~=ARGV[7] then return {'CORRUPT'} end
 return {r.state,r.id}
end
local user=redis.call('HGET',KEYS[2],uf)
if user=='CLAIMED' or redis.call('HEXISTS',KEYS[2],'c:'..ARGV[4])==1 then return {'CLAIMED'} end
if user and user~='' then
 local r=record(redis.call('HGET',KEYS[2],'r:'..user));if not r or r.student~=ARGV[4] or r.state~='HELD' then return {'CORRUPT'} end
 return {'PENDING',r.id}
end
local n=counters();if n[2]==0 then return {'SOLD_OUT'} end
local r={campaign=ARGV[7],generation=ARGV[1],id=ARGV[3],student=ARGV[4],key=ARGV[5],at=at,state='HELD'}
-- Validate first, then ONE mutation: runtime errors cannot leave an unidentifiable DECR.
redis.call('HSET',KEYS[2],rf,cjson.encode(r),uf,ARGV[3],kf,ARGV[3],'#free',tostring(n[2]-1),'#held',tostring(n[3]+1))
return {'HELD',ARGV[3]}
