package by.vs.dnaamrscreener.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Redis Testcontainers helper. Uses a single standalone instance for both
 * gene and session templates (production session is cluster; tests use standalone).
 */
public final class RedisContainerSupport {

    public static final String REDIS_IMAGE = "redis:7-alpine";

    private RedisContainerSupport() {}

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    public static GenericContainer<?> createRedisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--save", "", "--appendonly", "no");
    }

    public static StringRedisTemplate createTemplate(GenericContainer<?> redis) {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }
}
