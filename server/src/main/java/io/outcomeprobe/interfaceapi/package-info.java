/**
 * Control API 공통 문제 응답을 담당하는 interfaceapi 모듈이다.
 *
 * <p>업무 판단은 각 모듈의 Controller와 서비스에 위임하고, 이 모듈은
 * 예외→problem+json 변환 같은 공통 표현만 담당한다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.interfaceapi;
