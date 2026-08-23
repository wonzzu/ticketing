
local released = 0                                      -- 실제로 해제한 좌석 수
for i = 1, #KEYS do                                    -- 요청받은 좌석 Key 전체 순회
    if redis.call('GET', KEYS[i]) == ARGV[1] then      -- 현재 소유자가 요청한 memberId인지 확인
        redis.call('DEL', KEYS[i])                     -- 소유자가 일치할 때만 선점 Key 삭제
        released = released + 1                        -- 해제된 좌석 수 증가
    end
end

return released                                        -- 실제 해제 개수 반환