/**
 * Target Profile 신뢰 경계를 담당하는 profile 모듈이다.
 *
 * <p>Profile lookup과 path allowlist를 제공하며, 요청 직전에만 secretRef를 해석한다.
 * 해석한 secret 원문은 영속화·Evidence·Report·로그에 전달하지 않는다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.profile;
