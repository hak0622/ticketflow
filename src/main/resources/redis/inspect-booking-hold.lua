-- Read only; Redis TIME is authoritative for the lease. Never acquire here.
local typ = redis.call('TYPE', KEYS[3]).ok
if typ == 'none' then
  if redis.call('GET', KEYS[4]) == ARGV[2] then return redis.error_reply('Hold missing') end
  if redis.call('TYPE', KEYS[1]).ok ~= 'string' then return redis.error_reply('Seat key missing') end
  return -1
end
if typ ~= 'hash' or redis.call('HGET', KEYS[3], 'owner') ~= ARGV[1] then return redis.error_reply('Invalid hold owner/type') end
if redis.call('HGET', KEYS[3], 'state') ~= 'HELD' then return -1 end
if not redis.call('HGET', KEYS[3], 'holdVersion') then
 if redis.call('HEXISTS', KEYS[3], 'holdExpiresAt') == 1 then return redis.error_reply('Hold version missing') end
 return -2
end
if redis.call('HGET', KEYS[3], 'holdVersion') ~= '2' then return redis.error_reply('Unknown hold version') end
local deadline = tonumber(redis.call('HGET', KEYS[3], 'holdExpiresAt'))
local remaining = tonumber(redis.call('HGET', KEYS[3], 'remaining'))
if not deadline or not remaining or not redis.call('HGET', KEYS[3], 'admissionExpires')
 or redis.call('GET', KEYS[4]) ~= ARGV[2] or redis.call('TYPE', KEYS[1]).ok ~= 'string' then
 return redis.error_reply('Invalid hold metadata') end
local t = redis.call('TIME')
return math.max(0, deadline - (t[1] * 1000 + math.floor(t[2] / 1000)))
