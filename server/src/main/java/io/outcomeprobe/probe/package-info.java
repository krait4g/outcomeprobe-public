/**
 * HTTP/JSON State Probe와 assertion 평가를 담당하는 probe 모듈이다.
 *
 * <p>Probe는 Target의 외부 관측 상태만 조회하며 Target 저장소에 직접 접근하지 않는다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.probe;
