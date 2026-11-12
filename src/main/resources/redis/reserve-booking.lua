-- Inventory progress is independent of the hold deadline. Legacy records have neither field.
local function inventoryGuard(key)
  local version = redis.call('HGET', key, 'inventoryVersion')
  local phase = redis.call('HGET', key, 'inventoryPhase')
  if not version and not phase then return end
  if version ~= '1' or not phase then return redis.error_reply('INVENTORY_INVALID version/phase') end
  if phase == 'RESERVE_PENDING' or phase == 'RELEASE_PENDING' then
    return redis.error_reply('INVENTORY_INCOMPLETE ' .. phase)
  end
  local state = redis.call('HGET', key, 'state')
  local outcome = redis.call('HGET', key, 'releaseOutcome')
  local result = redis.call('HGET', key, 'result')
  if phase == 'HELD' and state == 'HELD' and not outcome and not result then return end
  if state == 'RELEASED' then
    if phase == 'RETURNED' and outcome == 'RETURNED' and not result then return end
    if phase == 'NOT_HELD' and outcome == 'NOT_HELD' and not result then return end
    if phase == 'SOLD_OUT' and result == 'SOLD_OUT' and not outcome then return end
  end
  return redis.error_reply('INVENTORY_INVALID state combination')
end
-- KEYS: remaining, admission, attempt hash, active user attempt. No key expires except admission.
local function kind(k) return redis.call('TYPE', k).ok end
local attemptType = kind(KEYS[3])
if attemptType ~= 'none' and attemptType ~= 'hash' then return redis.error_reply('Invalid attempt type') end
if attemptType == 'hash' then
  local invalid = inventoryGuard(KEYS[3]); if invalid then return invalid end
  if redis.call('HGET', KEYS[3], 'owner') ~= ARGV[1] then return redis.error_reply('Attempt owner mismatch') end
  if redis.call('HGET', KEYS[3], 'state') == 'HELD' then
    local version = redis.call('HGET', KEYS[3], 'holdVersion')
    if not version then
      if redis.call('HEXISTS', KEYS[3], 'holdExpiresAt') == 1 then return redis.error_reply('Hold version missing') end
      return -7
    end
    if version ~= '2' then return redis.error_reply('Unknown hold version') end
    if version == '2' then
      local deadline = tonumber(redis.call('HGET', KEYS[3], 'holdExpiresAt'))
      if not deadline or deadline ~= deadline or deadline % 1 ~= 0 or deadline <= 0 or deadline > 9007199254740991 then return redis.error_reply('Hold deadline missing') end
      local t = redis.call('TIME')
      if deadline <= t[1] * 1000 + math.floor(t[2] / 1000) then return -6 end
      if redis.call('GET', KEYS[4]) ~= ARGV[2] then return redis.error_reply('Active attempt mismatch') end
    end
    local current = redis.call('GET', KEYS[1])
    if not current or not string.match(current, '^%d+$') then return redis.error_reply('Seat key invalid') end
    return tonumber(current)
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
  redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'RELEASED', 'result', 'SOLD_OUT', 'inventoryVersion', '1', 'inventoryPhase', 'SOLD_OUT')
  return -1
end
local t = redis.call('TIME')
local expires = t[1] * 1000 + math.floor(t[2] / 1000) + ttl
if expires > 9007199254740991 then return redis.error_reply('Invalid admission expiry') end
-- Validate all types/values before the first write. Lua errors do not roll back writes.
redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'HELD', 'remaining', seats - 1, 'admissionExpires', expires, 'holdVersion', '2', 'holdExpiresAt', t[1] * 1000 + math.floor(t[2] / 1000) + 300000, 'inventoryVersion', '1', 'inventoryPhase', 'RESERVE_PENDING')
redis.call('SET', KEYS[4], ARGV[2])
redis.call('DECR', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[3], 'inventoryPhase', 'HELD')
return seats - 1
