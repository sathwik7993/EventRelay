-- Atomic token-bucket rate limiter.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (max tokens / burst)
-- ARGV[2] = refill rate (tokens per second)
-- ARGV[3] = now (epoch milliseconds)
-- ARGV[4] = requested tokens
-- Returns {allowed (1/0), remaining tokens}
local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    ts = now
end

-- Refill based on elapsed time since last check.
local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + elapsed * refill)

local allowed = 0
if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
-- Expire idle buckets: enough time to refill fully, plus a margin.
redis.call('PEXPIRE', key, math.ceil((capacity / refill) * 1000) + 1000)

return {allowed, math.floor(tokens)}
