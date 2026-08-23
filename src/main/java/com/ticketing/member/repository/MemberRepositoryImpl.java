package com.ticketing.member.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ticketing.member.domain.MemberStatus;
import com.ticketing.member.domain.MemberType;
import com.ticketing.member.dto.request.MemberSearchCond;
import com.ticketing.member.dto.response.MemberSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.ticketing.member.domain.QMember.member;

@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<MemberSearchResult> search(MemberSearchCond cond, Pageable pageable) {

        List<MemberSearchResult> result = queryFactory
                .select(Projections.constructor(
                        MemberSearchResult.class,
                        member.id,
                        member.email,
                        member.name,
                        member.phone,
                        member.memberType,
                        member.memberStatus,
                        member.createdAt
                ))
                .from(member)
                .where(
                        emailContains(cond.email()),
                        nameContains(cond.name()),
                        statusEq(cond.memberStatus()),
                        typeEq(cond.memberType())
                )
                .orderBy(member.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();


        JPAQuery<Long> countQuery = queryFactory
                .select(member.count())
                .from(member)
                .where(emailContains(cond.email()),
                        nameContains(cond.name()),
                        statusEq(cond.memberStatus()),
                        typeEq(cond.memberType()));

        return PageableExecutionUtils.getPage(result, pageable, countQuery::fetchOne);
    }


    private BooleanExpression emailContains(String email) {
        return StringUtils.hasText(email) ? member.email.contains(email) : null;
    }

    private BooleanExpression nameContains(String name) {
        return StringUtils.hasText(name) ? member.name.contains(name) : null;
    }

    private BooleanExpression statusEq(MemberStatus status) {
        return status != null ? member.memberStatus.eq(status) : null;
    }

    private BooleanExpression typeEq(MemberType type) {
        return type != null ? member.memberType.eq(type) : null;
    }
}
