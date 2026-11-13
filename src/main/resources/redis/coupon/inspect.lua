local problem=guard(false);if problem then return {problem} end
local n=counters();return {'HEALTHY',tostring(n[1]),tostring(n[2]),tostring(n[3]),tostring(n[4])}
