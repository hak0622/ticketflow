-- ARGV: owner, request id, restore admission (1/0), committed booking exists (1/0)
local function kind(k) return redis.call('TYPE', k).ok end
local typ = kind(KEYS[3])
if typ ~= 'hash' and typ ~= 'none' then return redis.error_reply('Invalid attempt type') end
if typ == 'none' then
  if ARGV[4] == '1' then return redis.error_reply('Committed booking hold missing; reconciliation required') end
  -- Even a request which never reserved needs a tombstone against late execution.
  redis.call('HSET', KEYS[3], 'owner', ARGV[1], 'state', 'RELEASED', 'releaseOutcome', 'NOT_HELD')
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
if not expires then return redis.error_reply('Admission expiry missing') end
redis.call('INCR', KEYS[1])
redis.call('HSET', KEYS[3], 'state', 'RELEASED', 'releaseOutcome', 'RETURNED')
redis.call('DEL', KEYS[4])
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
if ARGV[3] == '1' and expires > now and at == 'none' then
  redis.call('SET', KEYS[2], '1', 'PXAT', string.format('%.0f', expires), 'NX')
end
return 1
