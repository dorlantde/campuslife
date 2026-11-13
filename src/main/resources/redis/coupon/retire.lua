if not hash(KEYS[1]) or redis.call('HGET',KEYS[1],'generation')~=ARGV[1] or redis.call('HGET',KEYS[1],'token')~=ARGV[3] or not id(ARGV[4]) or not higher(ARGV[1],ARGV[4]) then return {'STALE'} end
redis.call('EXPIRE',KEYS[2],3600);return {'RETIRED'}
