-- 테스트 정리 스크립트 (@Sql AFTER_TEST_METHOD로 각 테스트 후 실행)
-- 모든 테이블 데이터를 비운다. FK 참조 순서 무시를 위해 체크를 잠시 끈다.
-- ※ 테이블명은 ddl-auto 기본 네이밍(snake_case) 기준 — 실제 스키마와 다르면 조정.
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE payment_history;
TRUNCATE TABLE processed_message;
TRUNCATE TABLE outbox_event;
TRUNCATE TABLE payment;
TRUNCATE TABLE reservation_history;
TRUNCATE TABLE reservation_seat;
TRUNCATE TABLE reservation;
TRUNCATE TABLE event_seat;
TRUNCATE TABLE event_schedule;
TRUNCATE TABLE event_review_history;
TRUNCATE TABLE event;
TRUNCATE TABLE seat;
TRUNCATE TABLE venue;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE coupon;
TRUNCATE TABLE member_history;
TRUNCATE TABLE review;
TRUNCATE TABLE daily_event_stats;
TRUNCATE TABLE daily_sales_stats;
TRUNCATE TABLE stats_dirty_date;
TRUNCATE TABLE settlement_detail;
TRUNCATE TABLE settlement;
TRUNCATE TABLE settlement_dirty_date;
TRUNCATE TABLE settlement_skip_log;
TRUNCATE TABLE normal_member;
TRUNCATE TABLE seller;
TRUNCATE TABLE admin_member;
TRUNCATE TABLE member;

SET FOREIGN_KEY_CHECKS = 1;
