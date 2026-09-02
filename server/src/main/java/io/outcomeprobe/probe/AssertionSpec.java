package io.outcomeprobe.probe;

/**
 * assertion 하나를 표현하는 값 객체다.
 *
 * <p>책임: JSONPath·연산자·기대 값을 묶어 Probe와 target.http 기대 검사가
 * 동일한 assertion 모델을 공유하게 한다.</p>
 *
 * @param jsonPath 검증할 JSONPath
 * @param operator 비교 연산자
 * @param expected 기대 값, EXISTS 계열은 null 가능
 */
public record AssertionSpec(String jsonPath, AssertionOperator operator, Object expected) {
}
