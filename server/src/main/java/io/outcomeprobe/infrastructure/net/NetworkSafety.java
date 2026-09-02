package io.outcomeprobe.infrastructure.net;

import jakarta.annotation.PreDestroy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 외부 요청 대상 host의 SSRF 위험을 검사한다.
 *
 * <p>책임: Target·Webhook·Probe 요청이 클라우드 metadata·link-local·
 * multicast·unspecified·IPv6 ULA 주소로 나가지 못하게 막는다.
 * loopback·로컬·Docker 사설망은 local 도구 특성상 허용한다.</p>
 * <p>핵심 불변식: 검사는 요청 직전 실제 name resolution의 모든 레코드로 수행해
 * DNS rebinding·멀티 A 레코드 우회를 줄인다. HTTP/HTTPS scheme만 허용한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 검사는 production SSRF 방어의 전부가
 * 아니다. OutcomeProbe는 local·CI 전용이며 최종 방어는 배포 환경 network
 * policy의 책임이다. DNS rebinding 완전 방어는 IP-pinning resolver가 필요하다.</p>
 */
@Component
public class NetworkSafety {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Duration DNS_RESOLUTION_TIMEOUT = Duration.ofSeconds(2);
    private static final int DNS_WORKERS = 2;
    private static final int DNS_QUEUE_CAPACITY = 16;

    private final AddressResolver addressResolver;
    private final ExecutorService dnsExecutor;
    private final Duration dnsResolutionTimeout;

    /**
     * bounded DNS executor와 시스템 resolver를 사용하는 기본 검사기를 만든다.
     */
    public NetworkSafety() {
        this(InetAddress::getAllByName, createDnsExecutor(), DNS_RESOLUTION_TIMEOUT);
    }

    /**
     * 테스트 가능한 resolver·executor·timeout으로 검사기를 만든다.
     *
     * @param addressResolver host 주소 해석기
     * @param dnsExecutor bounded DNS 실행기
     * @param dnsResolutionTimeout 한 번의 DNS 해석 상한
     */
    NetworkSafety(AddressResolver addressResolver, ExecutorService dnsExecutor,
                  Duration dnsResolutionTimeout) {
        this.addressResolver = java.util.Objects.requireNonNull(addressResolver);
        this.dnsExecutor = java.util.Objects.requireNonNull(dnsExecutor);
        this.dnsResolutionTimeout = java.util.Objects.requireNonNull(dnsResolutionTimeout);
        if (dnsResolutionTimeout.isZero() || dnsResolutionTimeout.isNegative()) {
            throw new IllegalArgumentException("DNS 해석 제한 시간은 0보다 커야 합니다.");
        }
    }

    /**
     * URI host가 안전한지 검사한다.
     *
     * <p>scheme이 HTTP/HTTPS가 아니면 즉시 거부한다. host의 모든 DNS 레코드를
     * 해석해 하나라도 위험하면 거부한다(멀티 A 레코드 우회 방지).</p>
     *
     * @param uri 요청 URI
     * @return 안전하면 true
     */
    public boolean isSafe(URI uri) {
        return assess(uri).safe();
    }

    /**
     * URI의 bounded DNS 해석 성공과 최종 SSRF 안전 판정을 분리해 반환한다.
     *
     * <p>연결 사전검사는 DNS 실패와 해석된 위험 주소를 서로 다른 check로 보여줘야 한다.
     * 실제 주소는 외부 응답이나 로그에 포함하지 않는다.</p>
     *
     * @param uri 검사할 HTTP(S) URI
     * @return DNS 해석 여부와 모든 주소의 안전 여부
     */
    public Assessment assess(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return new Assessment(false, false);
        }
        // HTTP/HTTPS 외 scheme(file, gopher 등)은 즉시 차단한다.
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT))) {
            return new Assessment(false, false);
        }
        Optional<InetAddress[]> resolved = resolveAddresses(uri.getHost());
        if (resolved.isEmpty() || resolved.get().length == 0) {
            return new Assessment(false, false);
        }
        // 모든 DNS 레코드를 검사해 위험 IP가 하나라도 있으면 차단한다.
        for (InetAddress address : resolved.get()) {
            if (!isAddressSafe(address)) {
                return new Assessment(true, false);
            }
        }
        return new Assessment(true, true);
    }

    /**
     * bounded DNS와 주소 안전성의 비민감 판정 결과다.
     *
     * @param resolved 제한 시간 안에 하나 이상의 주소를 해석했는지 여부
     * @param safe 해석된 모든 주소가 SSRF 정책을 통과했는지 여부
     */
    public record Assessment(boolean resolved, boolean safe) {
    }

    /**
     * DNS 해석을 bounded executor에서 실행하고 제한 시간을 넘기면 fail-closed한다.
     *
     * <p>호출자 thread가 lease보다 오래 DNS에 묶이지 않게 하며, 포화된 executor도 안전하지
     * 않은 대상으로 처리한다. timeout·중단 시 task 취소를 요청하지만 JVM resolver의 실제
     * native 호출이 즉시 중단된다고 보장하지는 않는다.</p>
     *
     * @param host 해석할 host
     * @return 제한 시간 안에 얻은 주소, 실패하면 empty
     */
    private Optional<InetAddress[]> resolveAddresses(String host) {
        Future<InetAddress[]> resolution;
        try {
            resolution = dnsExecutor.submit(() -> addressResolver.resolve(host));
        } catch (RejectedExecutionException exception) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(resolution.get(
                    dnsResolutionTimeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException exception) {
            resolution.cancel(true);
            return Optional.empty();
        } catch (InterruptedException exception) {
            resolution.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException exception) {
            return Optional.empty();
        }
    }

    /**
     * 해석된 주소의 안전성을 검사한다.
     *
     * <p>link-local·multicast·unspecified·IPv6 ULA(fc00::/7)를 차단한다.
     * loopback은 local·CI 도구 특성상 허용한다(Target·Consumer가 동일 호스트에서 실행됨).
     * IPv4-mapped IPv6(::ffff:a.b.c.d)는 IPv4로 재해석해 검사한다.
     * 이 처리가 없으면 169.254.x를 IPv6 표기로 우회할 수 있다(OP-AUDIT-SEC-001).</p>
     */
    private boolean isAddressSafe(InetAddress address) {
        // metadata 서비스(169.254.169.254)를 포함한 link-local·multicast·unspecified는 항상 차단한다.
        if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            return false;
        }
        // IPv6 ULA(fc00::/7)는 사설 인프라 접근을 막기 위해 차단한다.
        if (address instanceof Inet6Address ip6 && isUniqueLocalAddress(ip6)) {
            return false;
        }
        if (address instanceof Inet6Address ip6 && ip6.isIPv4CompatibleAddress()) {
            byte[] raw = address.getAddress();
            byte[] v4 = new byte[]{raw[12], raw[13], raw[14], raw[15]};
            try {
                InetAddress mapped = InetAddress.getByAddress(v4);
                if (mapped.isLinkLocalAddress() || mapped.isMulticastAddress()
                        || mapped.isAnyLocalAddress()) {
                    return false;
                }
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 주소가 ULA(fc00::/7) 범위인지 판정한다.
     */
    private boolean isUniqueLocalAddress(Inet6Address address) {
        byte[] raw = address.getAddress();
        return (raw[0] & 0xFE) == 0xFC;
    }

    /**
     * DNS native 호출이 멈춰도 thread·대기열 수가 무제한 증가하지 않는 executor를 만든다.
     *
     * @return daemon thread와 bounded queue를 사용하는 executor
     */
    private static ExecutorService createDnsExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                DNS_WORKERS, DNS_WORKERS, 5L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DNS_QUEUE_CAPACITY),
                Thread.ofPlatform().daemon(true).name("outcomeprobe-dns-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Spring bean 종료 시 남은 DNS task의 중단을 요청한다.
     */
    @PreDestroy
    public void shutdown() {
        dnsExecutor.shutdownNow();
    }

    /**
     * host 이름을 모든 주소로 해석하는 교체 가능한 경계다.
     */
    @FunctionalInterface
    interface AddressResolver {

        /**
         * host의 모든 주소를 반환한다.
         *
         * @param host 해석할 host
         * @return 해석된 모든 주소
         * @throws UnknownHostException host를 해석할 수 없으면 발생한다
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
