package com.ticketing.member.repository;

import com.ticketing.member.dto.request.MemberSearchCond;
import com.ticketing.member.dto.response.MemberSearchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRepositoryCustom {

    Page<MemberSearchResult> search(MemberSearchCond cond, Pageable pageable);
}
