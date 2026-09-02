package io.outcomeprobe.interfaceapi;

import io.outcomeprobe.execution.CapacityLimitExceededException;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.report.BundleExportException;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Control API 예외를 application/problem+json으로 변환하는 공통 handler다.
 *
 * <p>책임: 사용자가 수정 가능한 입력 오류는 4xx로, OutcomeProbe 내부 결함은
 * 500으로 구분해 stack trace나 secret이 응답에 새지 않게 한다.</p>
 * <p>핵심 불변식: FAILED 시나리오 판정과 제품 내부 ERROR는 서로 다른 문제 코드로
 * 보고한다. 이 handler는 입력·내부 오류만 다룬다.</p>
 */
@RestControllerAdvice
public class GlobalProblemHandler {

    /**
     * 기본 생성자다.
     */
    public GlobalProblemHandler() {
    }

    /**
     * Artifact 해석·import 실패를 400 problem 응답으로 변환한다.
     *
     * <p>DATABASE_CONFLICT 계열은 409로 보고해 재시도 정책을 구분할 수 있게 한다.</p>
     *
     * @param exception 발생한 import 예외
     * @return problem detail 응답
     */
    @ExceptionHandler(ArtifactImportException.class)
    public ProblemDetail handleArtifactImport(ArtifactImportException exception) {
        HttpStatus status = exception.problemCode() == ProblemCode.DATABASE_CONFLICT
                || exception.problemCode() == ProblemCode.RUN_IDEMPOTENCY_KEY_REUSED
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(URI.create("urn:outcomeprobe:problem:"
                + exception.problemCode().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(exception.problemCode().name());
        problem.setProperty("code", exception.problemCode().name());
        if (exception.field() != null) {
            problem.setProperty("field", exception.field());
        }
        return problem;
    }

    /**
     * 동시 실행 Run 수 상한 초과를 429 problem 응답으로 변환한다.
     *
     * <p>입력 자체는 유효하나 일시적 capacity 부족이므로 재시도 가능함을 나타낸다.</p>
     *
     * @param exception 발생한 capacity 예외
     * @return problem detail 응답
     */
    @ExceptionHandler(CapacityLimitExceededException.class)
    public ProblemDetail handleCapacityLimit(CapacityLimitExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        problem.setType(URI.create("urn:outcomeprobe:problem:capacity-limit-exceeded"));
        problem.setTitle("CAPACITY_LIMIT_EXCEEDED");
        problem.setProperty("code", "CAPACITY_LIMIT_EXCEEDED");
        return problem;
    }

    /**
     * strict Bundle 생성 거부를 저장 상태 충돌 또는 내부 오류로 변환한다.
     *
     * <p>legacy·손상 snapshot은 현재 Artifact로 보정할 수 없는 Run 상태 충돌이므로
     * 409를 반환한다. JSON·ZIP 직렬화 실패는 500으로 분류한다.</p>
     *
     * @param exception Bundle 생성 예외
     * @return secret 없는 problem detail 응답
     */
    @ExceptionHandler(BundleExportException.class)
    public ProblemDetail handleBundleExport(BundleExportException exception) {
        HttpStatus status = exception.problemCode() == ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED
                ? HttpStatus.CONFLICT
                : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(URI.create("urn:outcomeprobe:problem:"
                + exception.problemCode().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(exception.problemCode().name());
        problem.setProperty("code", exception.problemCode().name());
        return problem;
    }

    /**
     * 등록되지 않은 HTTP 경로를 일반 내부 오류가 아닌 404 problem 응답으로 변환한다.
     *
     * <p>core-only 배포에서 제거한 Pack API처럼 실제로 존재하지 않는 경로가 공통 500 handler에
     * 흡수되지 않도록 경계를 명시한다.</p>
     *
     * @param exception 등록되지 않은 정적 또는 API 경로 예외
     * @return 리소스 부재를 나타내는 problem detail 응답
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.");
        problem.setType(URI.create("urn:outcomeprobe:problem:resource-not-found"));
        problem.setTitle("RESOURCE_NOT_FOUND");
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return problem;
    }

    /**
     * 예상하지 못한 내부 오류를 secret 없는 500 problem 응답으로 변환한다.
     *
     * @param exception 발생한 예외
     * @return problem detail 응답
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleInternal(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류가 발생했습니다.");
        problem.setType(URI.create("urn:outcomeprobe:problem:internal-execution-error"));
        problem.setTitle(ProblemCode.INTERNAL_EXECUTION_ERROR.name());
        problem.setProperty("code", ProblemCode.INTERNAL_EXECUTION_ERROR.name());
        return problem;
    }
}
