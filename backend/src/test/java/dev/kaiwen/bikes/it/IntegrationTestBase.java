package dev.kaiwen.bikes.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：启动完整 Spring 上下文 + 真实 PostgreSQL 容器（Testcontainers）。
 *
 * <p>设计要点（参照 firmament-take-out 的集成测试方案）：
 * <ul>
 *   <li>{@code webEnvironment = RANDOM_PORT}：真实 servlet 容器（Tomcat）承载 HTTP 请求，
 *       测试用 {@code TestRestTemplate} 发真实 HTTP 调用，而非 MockMvc 的内存 dispatch。
 *       这样覆盖到的链路包括 DispatcherServlet、Spring Security 过滤器链、
 *       Jackson 序列化、Flyway 迁移、JPA/Hibernate 与真实 PostgreSQL 方言。</li>
 *
 *   <li>容器以「共享单例」方式手动启动（静态块 + start()），JVM 存活期内只启动一次，
 *       所有集成测试类复用同一组容器与同一个 Spring 上下文（Spring Test 的
 *       context-cache 机制），避免每类重启容器导致的启动开销。</li>
 *
 *   <li>容器地址通过 {@code @DynamicPropertySource} 注入数据源，覆盖
 *       {@code application-it.yaml} 里的占位值。</li>
 *
 *   <li>schema 由 Flyway 在容器就绪后自动迁移（{@code spring.flyway.enabled=true}），
 *       保证测试用的表结构与生产一致。</li>
 * </ul>
 *
 * <p>容器清理（CI 环境）：CI 上 Ryuk 被禁用（受限集群拉不起 privileged 容器），
 * 若 pod 被强杀则 JVM shutdown hook 来不及执行，容器会残留在宿主节点上。
 * 给容器打上本次构建的标签，Jenkins {@code post { always }} 据此精确清理，
 * 不会误删并发构建正在使用的容器。本地运行时环境变量缺省，标签不生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
public abstract class IntegrationTestBase {

    /** PostgreSQL 17 共享容器：静态块中手动 start()，JVM 存活期内只启动一次，所有子类复用。 */
    static final PostgreSQLContainer<?> POSTGRES;

    /**
     * 容器标签键：用于 Jenkins {@code post { always }} 的兜底清理。
     * 本地运行时环境变量缺省，标签不生效。
     */
    static final String BUILD_TAG_LABEL = "dev.kaiwen.it.build";

    static {
        String buildTag = System.getenv("DUBLIN_BIKES_IT_BUILD_TAG");
        POSTGRES =
                new PostgreSQLContainer<>(
                        DockerImageName.parse("postgres:17-alpine")
                                .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("dublinbikes_it")
                        .withUsername("test")
                        .withPassword("test")
                        .withReuse(false);
        if (buildTag != null && !buildTag.isBlank()) {
            POSTGRES.withLabel(BUILD_TAG_LABEL, buildTag);
        }
        // 启动失败会直接抛异常，测试无法继续。
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        // 数据源直连 PostgreSQL 容器
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.boot.test.web.client.TestRestTemplate restTemplate;
}
