-- IDs and generations remain decimal strings: Lua doubles cannot represent BIGINT IDs.
local function id(v)
 return type(v)=='string' and v:match('^%d+$') and v~='0' and v:sub(1,1)~='0' and (#v<19 or (#v==19 and v<='9223372036854775807'))
end
local function higher(a,b) return #a>#b or (#a==#b and a>b) end
local function rid(v) return type(v)=='string' and #v==32 and v:match('^[a-f0-9]+$') end
local function key(v) return type(v)=='string' and #v>=1 and #v<=64 and v:match('^[A-Za-z0-9_%-]+$') end
local function integer(v,max)
 if type(v)~='string' or not v:match('^%d+$') or (#v>1 and v:sub(1,1)=='0') then return nil end
 local n=tonumber(v);if not n or n>max or n~=math.floor(n) then return nil end;return n
end
local function hash(k) return redis.call('TYPE',k).ok=='hash' end
local function record(raw)
 if not raw or #raw>1024 then return nil end
 local ok,r=pcall(cjson.decode,raw)
 if not ok or type(r)~='table' or not rid(r.id) or not id(r.student) or not id(r.campaign) or not id(r.generation) or not key(r.key)
  or type(r.at)~='number' or r.at<0 or r.at>9007199254740991 or r.at~=math.floor(r.at)
  or (r.state~='HELD' and r.state~='SPENT' and r.state~='CLOSED') then return nil end
 return r
end
local function counters()
 local a=redis.call('HMGET',KEYS[2],'#total','#free','#held','#spent');local t=integer(a[1],100000);local f=integer(a[2],100000);local h=integer(a[3],100000);local s=integer(a[4],100000)
 if not t or t<1 or not f or not h or not s or f+h+s~=t then return nil end
 return {t,f,h,s}
end
local function guard(build,token)
 if not id(ARGV[1]) or not hash(KEYS[1]) or not hash(KEYS[2]) then return 'UNINITIALIZED' end
 if redis.call('PTTL',KEYS[1])~=-1 or redis.call('PTTL',KEYS[2])~=-1 then return 'CORRUPT' end
 local c=redis.call('HMGET',KEYS[1],'generation','run','phase','token')
 if c[1]~=ARGV[1] or c[2]~=ARGV[2] then return 'STALE' end
 if not rid(c[4]) then return 'CORRUPT' end
 if build then if c[3]~='BUILDING' or c[4]~=token then return 'STALE' end
 elseif c[3]~='OPEN' and c[3]~='PAUSED' then return 'CLOSED' end
 if not build then
  local z=redis.call('HMGET',KEYS[2],'#expectedHeld','#seenHeld','#expectedSpent','#seenSpent')
  if not integer(z[1],100000) or not integer(z[3],100000) or z[1]~=z[2] or z[3]~=z[4] then return 'CORRUPT' end
 end
 local e=redis.call('HMGET',KEYS[2],'#generation','#run','#token')
 if e[1]~=c[1] or e[2]~=c[2] or e[3]~=c[4] then return 'CORRUPT' end
 if not counters() then return 'CORRUPT' end
 return nil
end
