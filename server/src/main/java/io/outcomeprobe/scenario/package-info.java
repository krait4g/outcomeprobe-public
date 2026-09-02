/**
 * Artifact import·검증·계약·시나리오 정의를 담당하는 scenario 모듈이다.
 *
 * <p>contract·definition 하위 패키지는 다른 모듈이 소비하는 공개 API다.
 * 내장 Artifact seeding도 이 모듈 안에서 수행해 infrastructure와의 순환을 피한다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.scenario;
