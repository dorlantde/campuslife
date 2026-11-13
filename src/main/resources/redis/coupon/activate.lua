if not hash(KEYS[1]) or redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[1],'run')~=ARGV[2] or redis.call('HGET',KEYS[1],'token')~=ARGV[3] then return {'STALE'} end
if ARGV[4]~='OPEN' and ARGV[4]~='PAUSED' then return {'INVALID'} end
local phase=redis.call('HGET',KEYS[1],'phase')
if phase==ARGV[4] then local p=guard(false);if p then return {p} end;return {'ACTIVE'} end
local p=guard(true,ARGV[3]);if p then return {p} end
if redis.call('HGET',KEYS[2],'#expectedHeld')~=redis.call('HGET',KEYS[2],'#seenHeld') or redis.call('HGET',KEYS[2],'#expectedSpent')~=redis.call('HGET',KEYS[2],'#seenSpent') then return {'INCOMPLETE'} end
redis.call('HSET',KEYS[1],'phase',ARGV[4]);return {'ACTIVE'}
