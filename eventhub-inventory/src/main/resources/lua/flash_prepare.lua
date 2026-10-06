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

local function redis_now_us()
    local time = redis.call('TIME')
    return normalize_decimal(time[1] .. string.format('%06d', tonumber(time[2])))
end

local capacity = parse_inventory_integer(ARGV[1])
if capacity == nil
        or not is_unsigned_decimal(ARGV[2])
        or not is_unsigned_decimal(ARGV[3])
        or compare_decimal(ARGV[2], ARGV[3]) >= 0 then
    return 'STATE_CONFLICT'
end

if compare_decimal(redis_now_us(), ARGV[2]) >= 0 then
    return 'WINDOW_CLOSED'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    if redis.call('EXISTS', KEYS[2]) ~= 0 then
        return 'STATE_CONFLICT'
    end
    redis.call('HSET', KEYS[1],
            'state', 'PREPARED',
            'capacity', ARGV[1],
            'stock', ARGV[1],
            'saleStartUs', ARGV[2],
            'saleEndUs', ARGV[3])
    return 'PREPARED'
end

local metadata = redis.call('HMGET', KEYS[1],
        'state', 'capacity', 'stock', 'saleStartUs', 'saleEndUs')
local stored_capacity = parse_inventory_integer(metadata[2])
local stored_stock = parse_inventory_integer(metadata[3])
if metadata[1] ~= 'PREPARED'
        or stored_capacity == nil
        or stored_stock == nil
        or stored_stock > stored_capacity
        or metadata[2] ~= ARGV[1]
        or metadata[4] ~= ARGV[2]
        or metadata[5] ~= ARGV[3] then
    return 'STATE_CONFLICT'
end

return 'IDEMPOTENT_PREPARED'
