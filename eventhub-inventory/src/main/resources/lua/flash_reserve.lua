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

local function redis_now_us()
    local time = redis.call('TIME')
    return normalize_decimal(time[1] .. string.format('%06d', tonumber(time[2])))
end

if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'state') ~= 'PREPARED' then
    return 'NOT_PREPARED'
end

local metadata = redis.call('HMGET', KEYS[1],
        'capacity', 'stock', 'saleStartUs', 'saleEndUs')
local capacity = parse_inventory_integer(metadata[1])
if capacity == nil
        or not is_unsigned_decimal(metadata[3])
        or not is_unsigned_decimal(metadata[4])
        or compare_decimal(metadata[3], metadata[4]) >= 0 then
    return 'STATE_CONFLICT'
end

local now_us = redis_now_us()
if compare_decimal(now_us, metadata[3]) < 0 then
    return 'NOT_STARTED'
end
if compare_decimal(now_us, metadata[4]) >= 0 then
    return 'ENDED'
end

local current = redis.call('HGET', KEYS[2], ARGV[1])
if current then
    local prefix = string.sub(current, 1, 2)
    local identity = string.sub(current, 3)
    if prefix == 'P:' then
        if identity == '' then
            return 'STATE_CONFLICT'
        end
        if identity == ARGV[2] then
            return 'IDEMPOTENT_PENDING'
        end
        return 'ALREADY_ADMITTED'
    end
    if prefix == 'A:' or prefix == 'R:' then
        if not is_positive_decimal(identity) then
            return 'STATE_CONFLICT'
        end
        return 'ALREADY_ADMITTED'
    end
    return 'STATE_CONFLICT'
end

local stock = parse_inventory_integer(metadata[2])
if stock == nil or stock > capacity then
    return 'STATE_CONFLICT'
end
if stock <= 0 then
    return 'SOLD_OUT'
end

redis.call('HINCRBY', KEYS[1], 'stock', -1)
redis.call('HSET', KEYS[2], ARGV[1], 'P:' .. ARGV[2])
return 'ADMITTED'
