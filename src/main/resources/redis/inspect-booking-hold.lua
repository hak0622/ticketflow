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
-- Read only; Redis TIME is authoritative for the lease. Never acquire here.
local typ = redis.call('TYPE', KEYS[3]).ok
if typ == 'none' then
  if redis.call('GET', KEYS[4]) == ARGV[2] then return redis.error_reply('Hold missing') end
  if redis.call('TYPE', KEYS[1]).ok ~= 'string' then return redis.error_reply('Seat key missing') end
  return -1
end
if typ ~= 'hash' or redis.call('HGET', KEYS[3], 'owner') ~= ARGV[1] then return redis.error_reply('Invalid hold owner/type') end
local invalid = inventoryGuard(KEYS[3]); if invalid then return invalid end
if redis.call('HGET', KEYS[3], 'state') ~= 'HELD' then return -1 end
if not redis.call('HGET', KEYS[3], 'holdVersion') then
 if redis.call('HEXISTS', KEYS[3], 'holdExpiresAt') == 1 then return redis.error_reply('Hold version missing') end
 return -2
end
if redis.call('HGET', KEYS[3], 'holdVersion') ~= '2' then return redis.error_reply('Unknown hold version') end
local deadline = tonumber(redis.call('HGET', KEYS[3], 'holdExpiresAt'))
local remaining = tonumber(redis.call('HGET', KEYS[3], 'remaining'))
if not deadline or deadline ~= deadline or deadline % 1 ~= 0 or deadline <= 0 or deadline > 9007199254740991 or not remaining or not redis.call('HGET', KEYS[3], 'admissionExpires')
 or redis.call('GET', KEYS[4]) ~= ARGV[2] or redis.call('TYPE', KEYS[1]).ok ~= 'string' then
 return redis.error_reply('Invalid hold metadata') end
local t = redis.call('TIME')
return math.max(0, deadline - (t[1] * 1000 + math.floor(t[2] / 1000)))
