/**
 * append-only Evidence Timeline과 redaction을 담당하는 evidence 모듈이다.
 *
 * <p>Evidence 기록·조회 포트는 모든 업무 모듈이 사용하는 공유 API다.
 * Evidence는 생성 후 수정·삭제되지 않는다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.evidence;
