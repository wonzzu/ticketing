package com.ticketing.admin.dto.response;

import com.ticketing.member.domain.Member;
import com.ticketing.member.domain.MemberStatus;
import com.ticketing.member.domain.MemberType;
import com.ticketing.member.dto.response.MemberSearchResult;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AdminMemberListResponseDto {

    private Long id;
    private String email;
    private String name;
    private String phone;
    private MemberType memberType;
    private String memberTypeLabel;
    private MemberStatus memberStatus;
    private String memberStatusLabel;
    private LocalDateTime createdAt;

    public static AdminMemberListResponseDto from(Member member) {
        return AdminMemberListResponseDto.builder()
                .id(member.getId())
                .email(member.getEmail())
                .name(member.getName())
                .phone(member.getPhone())
                .memberType(member.getMemberType())
                .memberTypeLabel(member.getMemberType().getDescription())
                .memberStatus(member.getMemberStatus())
                .memberStatusLabel(member.getMemberStatus().getDescription())
                .createdAt(member.getCreatedAt())
                .build();
    }

    public static AdminMemberListResponseDto from(MemberSearchResult member) {
        return AdminMemberListResponseDto.builder()
                .id(member.id())
                .email(member.email())
                .name(member.name())
                .phone(member.phone())
                .memberType(member.memberType())
                .memberTypeLabel(member.memberType().getDescription())
                .memberStatus(member.memberStatus())
                .memberStatusLabel(member.memberStatus().getDescription())
                .createdAt(member.createdAt())
                .build();
    }

}
