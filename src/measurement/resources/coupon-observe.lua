-- Loaded after the pinned production common.lua. This chunk runs only as EVAL_RO.
local problem=guard(false);if problem then return {problem} end
local n=counters()
local result={'HEALTHY',redis.call('HGET',KEYS[1],'phase'),ARGV[1],ARGV[2],tostring(n[1]),tostring(n[2]),tostring(n[3]),tostring(n[4])}
local ok,rows=pcall(cjson.decode,ARGV[3]);if not ok or type(rows)~='table' or #rows>120 then return {'INVALID'} end
for _,r in ipairs(rows) do
 if not id(r.student) or not rid(r.reservation) then return {'INVALID'} end
 local raw=redis.call('HGET',KEYS[2],'r:'..r.reservation)
 if raw and #raw>1024 then return {'CORRUPT'} end
 table.insert(result,raw or '')
 table.insert(result,redis.call('HGET',KEYS[2],'c:'..r.student) or '')
end
return result
