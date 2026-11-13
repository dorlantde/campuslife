if not id(ARGV[1]) or not rid(ARGV[3]) or not ARGV[2]:match('^[a-f0-9]+$') then return {'INVALID'} end
-- Only the known control key in the dedicated dataset may be reset. SQL is already fenced.
-- An OOM after DEL leaves it absent, never admitting against a half-initialized epoch.
local typ=redis.call('TYPE',KEYS[1]).ok
if typ~='none' and typ~='hash' then redis.call('DEL',KEYS[1]) end
local old=redis.call('HGET',KEYS[1],'generation')
if old then
 if id(old) and higher(old,ARGV[1]) then return {'STALE'} end
 if id(old) and old==ARGV[1] then
  if redis.call('HGET',KEYS[1],'token')~=ARGV[3] or redis.call('HGET',KEYS[1],'run')~=ARGV[2] then return {'STALE'} end
  redis.call('PERSIST',KEYS[1]);return {'PREPARED',old}
 end
end
redis.call('HSET',KEYS[1],'generation',ARGV[1],'run',ARGV[2],'phase','BUILDING','token',ARGV[3])
redis.call('PERSIST',KEYS[1]);return {'PREPARED',(old and id(old)) and old or '0'}
