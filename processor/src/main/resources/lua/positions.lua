-- Java validates inputs and builds these keys. Read/compare the whole batch before writing.
local flags, writes, seen = {}, {}, {}
for i = 1, #KEYS / 2 do
    local latest = KEYS[(i - 1) * 2 + 1]
    local geo = KEYS[(i - 1) * 2 + 2]
    local argument = (i - 1) * 4 + 1
    local timestamp = tonumber(ARGV[argument])
    local previous = seen[latest]
    if previous == nil then
        previous = tonumber(redis.call('HGET', latest, 'position_ts') or '-1')
    end
    flags[i] = 0
    seen[latest] = previous
    if timestamp > previous then
        flags[i] = 1
        seen[latest] = timestamp
        local tenant, asset = string.match(latest, '^tenant:(%d+):asset:(%d+):latest$')
        writes[#writes + 1] = {
            latest, geo, ARGV[argument], ARGV[argument + 1], ARGV[argument + 2],
            ARGV[argument + 3], asset, 'positions:tenant:' .. tenant
        }
    end
end

for _, write in ipairs(writes) do
    redis.call('HSET', write[1], 'position_ts', write[3], 'position', write[6])
    redis.call('GEOADD', write[2], write[4], write[5], write[7])
    redis.call('PUBLISH', write[8], write[6])
end
return flags
