package com.ticketing.admin.service;

import com.ticketing.global.entity.Address;
import com.ticketing.member.domain.Gender;
import com.ticketing.member.domain.MemberStatus;
import com.ticketing.member.domain.MemberType;
import com.ticketing.member.domain.NormalMember;
import com.ticketing.member.dto.request.MemberSearchCond;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("관리자 회원 검색 DTO Projection")
class AdminMemberSearchProjectionTest {

    @Autowired AdminMemberService adminMemberService;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager em;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            NormalMember member = NormalMember.create(
                    "projection@test.com", "pw", "프로젝션회원", "프로젝션",
                    LocalDate.of(2000, 1, 1), Gender.MALE, "010-1111-2222",
                    new Address("서울", "테스트로 1", "00000")
            );
            em.persist(member);
            em.flush();
            em.clear();
        });
    }

    @Test
    @DisplayName("목록 필드만 조회하고 Member 엔티티는 로딩하지 않는다")
    void search_projects_list_fields_without_loading_member_entities() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var result = adminMemberService.search(
                new MemberSearchCond("projection@test.com", null, MemberStatus.PENDING, MemberType.NORMAL),
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).singleElement().satisfies(member -> {
            assertThat(member.getEmail()).isEqualTo("projection@test.com");
            assertThat(member.getName()).isEqualTo("프로젝션회원");
            assertThat(member.getMemberTypeLabel()).isEqualTo("일반 회원");
            assertThat(member.getMemberStatusLabel()).isEqualTo("이메일 인증 전");
        });
        assertThat(statistics.getEntityLoadCount()).isZero();
    }
}
