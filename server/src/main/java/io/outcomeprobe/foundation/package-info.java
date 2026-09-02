/**
 * 모듈 공통 최소 타입(Clock·ID·해시·문제 코드)을 제공하는 foundation 모듈이다.
 *
 * <p>이 모듈은 업무 규칙을 담지 않으며 다른 모든 모듈이 의존할 수 있는
 * 공유 커널 역할을 한다.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package io.outcomeprobe.foundation;
