local problem=guard(true,ARGV[3]);if problem then return {problem} end
local batch=integer(ARGV[4],10000000);local next=integer(redis.call('HGET',KEYS[2],'#next'),10000000)
if not batch or not next or not ARGV[5]:match('^[a-f0-9]+$') then return {'INVALID'} end
if batch<next then if redis.call('HGET',KEYS[2],'b:'..ARGV[4])~=ARGV[5] then return {'CORRUPT'} end;return {'SEEDED'} end
if batch~=next then return {'STALE'} end
local ok,entries=pcall(cjson.decode,ARGV[6]);if not ok or type(entries)~='table' or #entries<1 or #entries>200 then return {'INVALID'} end
local changes={};local used={};local held=0;local spent=0
local function add(k,v)
 if used[k] or redis.call('HEXISTS',KEYS[2],k)==1 then return false end
 used[k]=true;table.insert(changes,k);table.insert(changes,v);return true
end
for _,v in ipairs(entries) do
 if not id(v.student) or v.campaign~=ARGV[7] or v.generation~=ARGV[1] or (v.kind~='HELD' and v.kind~='SPENT') then return {'INVALID'} end
 if v.kind=='HELD' and (not rid(v.id) or not key(v.key) or type(v.at)~='number' or v.at<0 or v.at~=math.floor(v.at)) then return {'INVALID'} end
 if not add('u:'..v.student,v.kind=='HELD' and v.id or 'CLAIMED') then return {'CORRUPT'} end
 if v.kind=='HELD' then
  if not rid(v.id) or not key(v.key) or type(v.at)~='number' or v.at<0 or v.at~=math.floor(v.at) then return {'INVALID'} end
  local r={id=v.id,student=v.student,campaign=v.campaign,generation=ARGV[1],key=v.key,at=v.at,state='HELD'}
  if not add('r:'..v.id,cjson.encode(r)) or not add('k:'..v.student..':'..v.key,v.id) then return {'CORRUPT'} end
  held=held+1
 elseif v.kind=='SPENT' then
  if not id(v.coupon) or not add('c:'..v.student,v.coupon) then return {'INVALID'} end
  if v.id and v.id~=cjson.null then
   if not rid(v.id) or not key(v.key) or type(v.at)~='number' or v.at<0 or v.at~=math.floor(v.at) then return {'INVALID'} end
   local r={id=v.id,student=v.student,campaign=v.campaign,generation=ARGV[1],key=v.key,at=v.at,state='SPENT'}
   if not add('r:'..v.id,cjson.encode(r)) or not add('k:'..v.student..':'..v.key,v.id) then return {'CORRUPT'} end
  end
  spent=spent+1
 else return {'INVALID'} end
end
local eh=integer(redis.call('HGET',KEYS[2],'#expectedHeld'),100000);local es=integer(redis.call('HGET',KEYS[2],'#expectedSpent'),100000)
local sh=integer(redis.call('HGET',KEYS[2],'#seenHeld'),100000);local ss=integer(redis.call('HGET',KEYS[2],'#seenSpent'),100000)
if not eh or not es or not sh or not ss or sh+held>eh or ss+spent>es then return {'CORRUPT'} end
-- Metadata fields are not part of the uniqueness guard for record fields.
table.insert(changes,'#seenHeld');table.insert(changes,tostring(sh+held));table.insert(changes,'#seenSpent');table.insert(changes,tostring(ss+spent));table.insert(changes,'#next');table.insert(changes,tostring(next+1));table.insert(changes,'b:'..ARGV[4]);table.insert(changes,ARGV[5])
redis.call('HSET',KEYS[2],unpack(changes));return {'SEEDED'}
