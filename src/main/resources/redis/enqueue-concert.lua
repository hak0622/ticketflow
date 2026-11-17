-- Existing ticket existence remains compatible with the previous queue path.
-- Booking independently validates the remaining admission TTL before reserving stock.
local queueType = redis.call('TYPE', KEYS[1]).ok
local ticketType = redis.call('TYPE', KEYS[2]).ok
if queueType ~= 'none' and queueType ~= 'zset' then return redis.error_reply('Invalid queue type') end
if ticketType ~= 'none' and ticketType ~= 'string' then return redis.error_reply('Invalid admission type') end
local score = tonumber(ARGV[2])
if not score or score ~= score or score < 0 or score > 9007199254740991 or score ~= math.floor(score) then
  return redis.error_reply('Invalid queue score')
end
if not ARGV[1] or ARGV[1] == '' then return redis.error_reply('Invalid queue member') end

if ticketType == 'string' then
  redis.call('ZREM', KEYS[1], ARGV[1])
  return {2, 0, redis.call('ZCARD', KEYS[1])}
end
redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
return {1, redis.call('ZRANK', KEYS[1], ARGV[1]) + 1, redis.call('ZCARD', KEYS[1])}
