/**
 * JSONB 변환·info 기여 같은 공유 인프라를 제공하는 infrastructure 모듈이다.
 *
 * <p>persistence 경계 지원 타입은 이 모듈의 API로 노출되며, 다른 모듈의
 * adapter는 이 타입을 사용할 수 있다. 이 모듈은 업무 모듈을 의존하지 않는다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.infrastructure;
