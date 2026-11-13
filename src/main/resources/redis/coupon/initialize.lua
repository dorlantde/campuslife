if not hash(KEYS[1]) or redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[1],'run')~=ARGV[2] or redis.call('HGET',KEYS[1],'token')~=ARGV[3] or redis.call('HGET',KEYS[1],'phase')~='BUILDING' then return {'STALE'} end
local t=integer(ARGV[4],100000);local h=integer(ARGV[5],100000);local s=integer(ARGV[6],100000)
if not t or t<1 or not h or not s or h+s>t then return {'INVALID'} end
local typ=redis.call('TYPE',KEYS[2]).ok
if typ~='none' then
 if typ~='hash' or redis.call('HGET',KEYS[2],'#token')~=ARGV[3] then return {'CORRUPT'} end
 return {'INITIALIZED'}
end
redis.call('HSET',KEYS[2],'#generation',ARGV[1],'#run',ARGV[2],'#token',ARGV[3],'#total',ARGV[4],'#free',tostring(t-h-s),'#held',ARGV[5],'#spent',ARGV[6],'#expectedHeld',ARGV[5],'#expectedSpent',ARGV[6],'#seenHeld','0','#seenSpent','0','#next','0')
return {'INITIALIZED'}
