local problem=guard(false);if problem then return {problem} end
if not ARGV[3]:match('^%d+$') then return {'INVALID'} end
local page=redis.call('HSCAN',KEYS[2],ARGV[3],'MATCH','r:*','COUNT',100);return {'PAGE',page[1],page[2]}
