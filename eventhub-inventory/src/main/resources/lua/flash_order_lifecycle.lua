local function is_unsigned_decimal(value)
    return type(value) == 'string' and string.match(value, '^%d+$') ~= nil
end

local function normalize_decimal(value)
    local normalized = string.gsub(value, '^0+', '')
    if normalized == '' then
        return '0'
    end
    return normalized
end

local function compare_decimal(left, right)
    left = normalize_decimal(left)
    right = normalize_decimal(right)
    if string.len(left) < string.len(right) then
        return -1
    end
    if string.len(left) > string.len(right) then
        return 1
    end
    if left < right then
        return -1
    end
    if left > right then
        return 1
    end
    return 0
end

local function parse_inventory_integer(value)
    if not is_unsigned_decimal(value) then
        return nil
    end
    local parsed = tonumber(value)
    if parsed == nil or parsed > 2147483647 then
        return nil
    end
    return parsed
end

local function is_positive_decimal(value)
    return is_unsigned_decimal(value) and compare_decimal(value, '0') > 0
end

local operation = ARGV[1]
if (operation ~= 'RELEASE' and operation ~= 'REFUND')
        or not is_positive_decimal(ARGV[2])
        or not is_positive_decimal(ARGV[3]) then
    return 'STATE_CONFLICT'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NOT_PREPARED'
end
if redis.call('HGET', KEYS[1], 'state') ~= 'PREPARED' then
    return 'STATE_CONFLICT'
end

local metadata = redis.call('HMGET', KEYS[1], 'capacity', 'stock')
local capacity = parse_inventory_integer(metadata[1])
local stock = parse_inventory_integer(metadata[2])
if capacity == nil or stock == nil or stock > capacity then
    return 'STATE_CONFLICT'
end

local current = redis.call('HGET', KEYS[2], ARGV[2])
if operation == 'RELEASE' and not current then
    return 'IDEMPOTENT_RELEASED'
end
if operation == 'REFUND' and not current then
    return 'FENCE_CONFLICT'
end

local prefix = string.sub(current, 1, 2)
local identity = string.sub(current, 3)
if prefix == 'P:' then
    if identity == '' then
        return 'STATE_CONFLICT'
    end
    return 'FENCE_CONFLICT'
end
if prefix ~= 'A:' and prefix ~= 'R:' then
    return 'STATE_CONFLICT'
end
if not is_positive_decimal(identity) then
    return 'STATE_CONFLICT'
end

if operation == 'RELEASE' then
    if prefix ~= 'A:' or identity ~= ARGV[3] then
        return 'FENCE_CONFLICT'
    end
    if stock >= capacity then
        return 'STATE_CONFLICT'
    end
    redis.call('HDEL', KEYS[2], ARGV[2])
    redis.call('HINCRBY', KEYS[1], 'stock', 1)
    return 'RELEASED'
end

if prefix == 'R:' then
    if identity == ARGV[3] then
        return 'IDEMPOTENT_REFUNDED'
    end
    return 'FENCE_CONFLICT'
end
if identity ~= ARGV[3] then
    return 'FENCE_CONFLICT'
end
if stock >= capacity then
    return 'STATE_CONFLICT'
end
redis.call('HSET', KEYS[2], ARGV[2], 'R:' .. ARGV[3])
redis.call('HINCRBY', KEYS[1], 'stock', 1)
return 'REFUNDED'
