-- The API supplies positive rate/cost values; this hash is owned only by this limiter.
local rate = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local windowMs = tonumber(ARGV[3]) or 1000
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local stored = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at')
local tokens = tonumber(stored[1]) or rate
local previous = tonumber(stored[2]) or now
local effectiveNow = math.max(now, previous)
tokens = math.min(rate, tokens + (effectiveNow - previous) * rate / windowMs)

local allowed, retry = 0, 0
if cost <= tokens then
    tokens = tokens - cost
    allowed = 1
elseif cost > rate then
    retry = -1
else
    retry = math.ceil((cost - tokens) * windowMs / rate)
end
local tokensText = string.format('%.17g', tokens)
local nowText = string.format('%.0f', effectiveNow)
local ttl = string.format('%.0f', math.max(2 * windowMs, math.ceil(effectiveNow - now + 2 * windowMs)))

redis.call('HSET', KEYS[1], 'tokens', tokensText, 'updated_at', nowText)
redis.call('PEXPIRE', KEYS[1], ttl)
return {allowed, retry}
