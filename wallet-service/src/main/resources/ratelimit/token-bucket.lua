-- Token bucket, evaluated entirely inside Redis.
--
-- Redis runs a script atomically: no other command interleaves with it. That is the whole reason
-- this is Lua and not Java. Reading the bucket, deciding, and writing it back from the application
-- would be a read-modify-write across three round trips, and two concurrent requests could both
-- read the same remaining count and both be allowed - letting a user exceed capacity precisely
-- when they are trying hardest to, which is the case the limiter exists for.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity            maximum tokens the bucket can hold
-- ARGV[2] refillPerSecond     tokens added per second
-- ARGV[3] nowMillis           caller's clock (see RateLimiter for why not redis TIME)
-- ARGV[4] requested           tokens this request costs
--
-- returns { allowed(1|0), remaining, millisUntilNextToken, millisUntilFull }

local key             = KEYS[1]
local capacity        = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local now             = tonumber(ARGV[3])
local requested       = tonumber(ARGV[4])

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

-- An absent bucket is a full one: a user who has not been seen owes nothing.
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- Refill for elapsed time. Clamped at 0 so a clock that goes backwards cannot remove tokens the
-- user has already earned.
local elapsedMillis = now - ts
if elapsedMillis < 0 then
  elapsedMillis = 0
end
tokens = math.min(capacity, tokens + (elapsedMillis / 1000.0) * refillPerSecond)

local allowed = 0
if tokens >= requested then
  allowed = 1
  tokens = tokens - requested
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- How long until the bucket refills to one token, and to full.
local millisUntilNextToken = 0
if tokens < 1 then
  millisUntilNextToken = math.ceil(((1 - tokens) / refillPerSecond) * 1000)
end

local millisUntilFull = 0
if tokens < capacity then
  millisUntilFull = math.ceil(((capacity - tokens) / refillPerSecond) * 1000)
end

-- Expire idle buckets rather than accumulating a key per user forever. Safe because an expired
-- bucket is recreated at full capacity, which is exactly where an idle user's bucket would have
-- refilled to anyway. +1s of slack so the key never dies while still meaningfully depleted.
redis.call('PEXPIRE', key, millisUntilFull + 1000)

return { allowed, math.floor(tokens), millisUntilNextToken, millisUntilFull }
