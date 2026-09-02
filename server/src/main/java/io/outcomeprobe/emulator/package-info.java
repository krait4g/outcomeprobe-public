/**
 * Provider Emulator ingress·truth·fault 주입을 담당하는 emulator 모듈이다.
 *
 * <p>alias lock·expectation·resource 포트는 모듈 API로 노출되며, execution
 * 모듈이 이 포트를 통해 Provider 상호작용을 등록·판정한다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.emulator;
