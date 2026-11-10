-- KEYS: remaining, admission, attempt hash, active user attempt. No key expires except admission.
local function kind(k) return redis.call('TYPE', k).ok end
local attemptType = kind(KEYS[3])
if attemptType ~= 'none' and attemptType ~= 'hash' then return redis.error_reply('Invalid attempt type') end
if attemptType == 'hash' then
  if redis.call('HGET', KEYS[3], 'owner') ~= ARGV[1] then return redis.error_reply('Attempt owner mismatch') end
  if redis.call('HGET', KEYS[3], 'state') == 'HELD' then
    return tonumber(redis.call('HGET', KEYS[3], 'remaining'))
  end
  if redis.call('HGET', KEYS[3], 'state') == 'RELEASED' and redis.call('HGET', KEYS[3], 'result') == 'SOLD_OUT' then return -1 end
  return -4 -- tombstone: delayed reserve must never acquire again
end
if kind(KEYS[1]) ~= 'string' then return redis.error_reply('Seat key missing or invalid') end
local raw = redis.call('GET', KEYS[1])
if not string.match(raw, '^%d+$') or #raw > 9 then return redis.error_reply('Invalid seat count') end
local admissionType = kind(KEYS[2])
local activeType = kind(KEYS[4])
if admissionType ~= 'none' and admissionType ~= 'string' then return redis.error_reply('Invalid admission type') end
if activeType ~= 'none' and activeType ~= 'string' then return redis.error_reply('Invalid active attempt type') end
if activeType ~= 'none' then return -5 end
local ttl = redis.call('PTTL', KEYS[2])
if ttl <= 0 then return -3 end
local seats = tonumber(raw)
if seats <= 0 then
  redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'RELEASED', 'result', 'SOLD_OUT')
  return -1
end
local t = redis.call('TIME')
local expires = t[1] * 1000 + math.floor(t[2] / 1000) + ttl
-- Validate all types/values before the first write. Lua errors do not roll back writes.
redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'HELD', 'remaining', seats - 1, 'admissionExpires', expires)
redis.call('SET', KEYS[4], ARGV[2])
redis.call('DECR', KEYS[1])
redis.call('DEL', KEYS[2])
return seats - 1
