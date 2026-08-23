package com.ticketing.member.dto.response;

import com.ticketing.member.domain.MemberStatus;
import com.ticketing.member.domain.MemberType;

import java.time.LocalDateTime;

public record MemberSearchResult(
        Long id,
        String email,
        String name,
        String phone,
        MemberType memberType,
        MemberStatus memberStatus,
        LocalDateTime createdAt
) {
}
