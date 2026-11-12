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
-- ARGV: owner, request id, restore admission (1/0), committed booking exists (1/0)
local function kind(k) return redis.call('TYPE', k).ok end
local typ = kind(KEYS[3])
if typ ~= 'hash' and typ ~= 'none' then return redis.error_reply('Invalid attempt type') end
if typ == 'hash' then local invalid = inventoryGuard(KEYS[3]); if invalid then return invalid end end
if typ == 'none' then
  if ARGV[4] == '1' then return redis.error_reply('Committed booking hold missing; reconciliation required') end
  -- Even a request which never reserved needs a tombstone against late execution.
  redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'RELEASED', 'releaseOutcome', 'NOT_HELD', 'inventoryVersion', '1', 'inventoryPhase', 'NOT_HELD')
  return 0
end
if redis.call('HGET', KEYS[3], 'owner') ~= ARGV[1] then return redis.error_reply('Attempt owner mismatch') end
local state = redis.call('HGET', KEYS[3], 'state')
if state == 'RELEASED' then
  local outcome = redis.call('HGET', KEYS[3], 'releaseOutcome')
  local result = redis.call('HGET', KEYS[3], 'result')
  if result == 'SOLD_OUT' then
    if outcome or redis.call('HEXISTS', KEYS[3], 'remaining') == 1 or redis.call('HEXISTS', KEYS[3], 'admissionExpires') == 1 then
      return redis.error_reply('Invalid sold-out marker')
    end
    return 0
  end
  if result then return redis.error_reply('Invalid release result') end
  if outcome == 'RETURNED' then return 2 end
  if outcome == 'NOT_HELD' then
    if ARGV[4] == '1' or redis.call('HEXISTS', KEYS[3], 'remaining') == 1 or redis.call('HEXISTS', KEYS[3], 'admissionExpires') == 1 then
      return redis.error_reply('Invalid non-held marker')
    end
    return 0
  end
  -- Old RELEASED markers cannot prove no hold. Conservatively retry post-processing.
  if not outcome then return 3 end
  return redis.error_reply('Invalid release outcome')
end
if state ~= 'HELD' then return redis.error_reply('Invalid attempt state') end
if kind(KEYS[1]) ~= 'string' then return redis.error_reply('Seat key missing or invalid') end
local raw = redis.call('GET', KEYS[1])
if not string.match(raw, '^%d+$') or #raw > 9 then return redis.error_reply('Invalid seat count') end
local at = kind(KEYS[2])
local ut = kind(KEYS[4])
if at ~= 'none' and at ~= 'string' then return redis.error_reply('Invalid admission type') end
if ut ~= 'string' or redis.call('GET', KEYS[4]) ~= ARGV[2] then return redis.error_reply('Active attempt mismatch') end
local expires = tonumber(redis.call('HGET', KEYS[3], 'admissionExpires'))
if not expires or expires ~= expires or expires % 1 ~= 0 or expires < 0 or expires > 9007199254740991 then return redis.error_reply('Admission expiry missing') end
if tonumber(raw) >= 999999999 then return redis.error_reply('Seat count overflow') end
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local expiryArgument = string.format('%.0f', expires)
redis.call('HSET', KEYS[3], 'inventoryVersion', '1', 'inventoryPhase', 'RELEASE_PENDING')
redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[4])
if ARGV[3] == '1' and expires > now and at == 'none' then
  redis.call('SET', KEYS[2], '1', 'PXAT', expiryArgument, 'NX')
end
redis.call('HSET', KEYS[3], 'state', 'RELEASED', 'releaseOutcome', 'RETURNED', 'inventoryPhase', 'RETURNED')
return 1
