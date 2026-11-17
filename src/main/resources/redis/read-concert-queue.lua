-- Read only: do not clean up coexistence or refresh the admission TTL here.
local queueType = redis.call('TYPE', KEYS[1]).ok
local ticketType = redis.call('TYPE', KEYS[2]).ok
if queueType ~= 'none' and queueType ~= 'zset' then return redis.error_reply('Invalid queue type') end
if ticketType ~= 'none' and ticketType ~= 'string' then return redis.error_reply('Invalid admission type') end
local total = redis.call('ZCARD', KEYS[1])
if ticketType == 'string' then return {2, 0, total} end
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then return {1, rank + 1, total} end
return {0, 0, total}
