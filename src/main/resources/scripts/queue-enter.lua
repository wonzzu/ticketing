

-- KEYS: active, waiting, sequence, schedules | ARGV: memberId, expireAt, capacity, now, scheduleId
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[4]) -- 만료된 Active 제거

if redis.call('ZSCORE', KEYS[1], ARGV[1]) then -- 이미 Active이면 즉시 입장 성공 반환
    return 1
end

if redis.call('ZSCORE', KEYS[2], ARGV[1]) then -- 이미 Waiting이면 중복 등록 방지
    return 0
end

if redis.call('ZCARD', KEYS[2]) == 0
        and redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) then -- 대기자가 없고 여석이 있으면
    redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]) -- Active 즉시 입장
    redis.call('SADD', KEYS[4], ARGV[5]) -- 승급 대상 일정 등록
    return 1
end

local sequence = redis.call('INCR', KEYS[3]) -- 다음 대기 순번 발급
redis.call('ZADD', KEYS[2], sequence, ARGV[1]) -- Waiting 등록
redis.call('SADD', KEYS[4], ARGV[5]) -- 승급 대상 일정 등록

return 0 -- Waiting 상태 반환
