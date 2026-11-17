local queueType = redis.call('TYPE', KEYS[1]).ok
if queueType ~= 'none' and queueType ~= 'zset' then return redis.error_reply('Invalid queue type') end
local count = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
if not count or count < 1 or count > 2147483647 or count ~= math.floor(count) then
  return redis.error_reply('Invalid queue batch size')
end
if not ttl or ttl < 1 or ttl > 2147483647 or ttl ~= math.floor(ttl) then
  return redis.error_reply('Invalid admission TTL')
end
if not ARGV[3] or ARGV[3] == '' then return redis.error_reply('Invalid admission prefix') end
local users = redis.call('ZRANGE', KEYS[1], 0, count - 1)
local granted = {}
-- Validate the whole selected batch before removing even its first member.
-- Lua execution is atomic, but errors do not roll back preceding writes.
for i = 1, #users do
  local ticketType = redis.call('TYPE', ARGV[3] .. users[i]).ok
  if ticketType ~= 'none' and ticketType ~= 'string' then
    return redis.error_reply('Invalid admission type')
  end
  if ticketType == 'none' then granted[#granted + 1] = users[i] end
end
for i = 1, #granted do
  redis.call('SETEX', ARGV[3] .. granted[i], ttl, '1')
end
-- Remove only selected members, without an unbounded refill of skipped admissions.
for i = 1, #users do redis.call('ZREM', KEYS[1], users[i]) end
return granted
