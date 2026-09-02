/**
 * Run·Step durable orchestration과 lease를 담당하는 execution 모듈이다.
 *
 * <p>Step 실행기와 Run 엔진이 이 모듈에 거주하며, scenario·emulator·evidence·
 * probe·profile 모듈의 API를 조합해 Scenario를 실행한다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.execution;
