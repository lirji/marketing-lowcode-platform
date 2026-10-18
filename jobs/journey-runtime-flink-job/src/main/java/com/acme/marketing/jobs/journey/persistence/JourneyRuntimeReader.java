package com.acme.marketing.jobs.journey.persistence;

import com.acme.marketing.jobs.journey.JourneyInfrastructureException;
import com.acme.marketing.jobs.support.JobEnvironment;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 每个 Flink 子任务独享只读会话；连接、网络和查询均有超时，不创建共享无界连接池。 */
public final class JourneyRuntimeReader {
    private static final String MAPPER_RESOURCE = "mapper/JourneyRuntimeMapper.xml";
    private final SqlSessionFactory sessions;

    /** 只在任务运行时初始化非序列化资源，序列化作业时不携带连接。 */
    public JourneyRuntimeReader(String url, String user, String password) {
        int connectMs = timeout("JOURNEY_DB_CONNECT_TIMEOUT_MS", 2_000);
        int socketMs = timeout("JOURNEY_DB_SOCKET_TIMEOUT_MS", 3_000);
        int querySeconds = timeout("JOURNEY_DB_QUERY_TIMEOUT_SECONDS", 2);
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", boundedUrl(url, connectMs, socketMs), user, password);
        var configuration = new Configuration(new Environment("journey-runtime", new JdbcTransactionFactory(), dataSource));
        configuration.setDefaultStatementTimeout(querySeconds);
        try (var resource = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
            new XMLMapperBuilder(resource, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
        } catch (IOException invalid) {
            throw new JourneyInfrastructureException(invalid);
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 读取开关；查询失败不能回退到默认关闭。 */
    public JourneyRuntimeMapper.KillRow killSwitch(String tenantId, String namespace) {
        return read(mapper -> mapper.killSwitch(tenantId, namespace));
    }

    /** 读取当前激活指针。 */
    public JourneyRuntimeMapper.CurrentRow current(String tenantId, String environment, String cell, String namespace) {
        return read(mapper -> mapper.current(tenantId, environment, cell, namespace));
    }

    /** 按完整历史键读取制品及激活签名。 */
    public JourneyRuntimeMapper.RuntimeRow activated(String tenantId, String environment, String cell, String namespace,
            long generation, long sequence, String artifactId) {
        return read(mapper -> mapper.activated(tenantId, environment, cell, namespace, generation, sequence, artifactId));
    }

    private <T> T read(Function<JourneyRuntimeMapper, T> query) {
        try (var session = sessions.openSession()) {
            return query.apply(session.getMapper(JourneyRuntimeMapper.class));
        } catch (PersistenceException unavailable) {
            throw new JourneyInfrastructureException(unavailable);
        }
    }

    private static int timeout(String name, int fallback) {
        int value = Integer.parseInt(JobEnvironment.value(name, Integer.toString(fallback)));
        int maximum = name.endsWith("SECONDS") ? 30 : 30_000;
        if (value < 1 || value > maximum) throw new IllegalArgumentException(name + " is outside timeout limits");
        return value;
    }

    /** Connector/J 的 URL 参数优先级较高；显式归一化，不能让旧 URL 中的 0 关闭超时。 */
    static String boundedUrl(String url, int connectMs, int socketMs) {
        String[] parts = url.split("\\?", 2);
        String options = parts.length < 2 ? "" : Arrays.stream(parts[1].split("&"))
                .filter(part -> !part.isBlank() && !part.regionMatches(true, 0, "connectTimeout=", 0, 15)
                        && !part.regionMatches(true, 0, "socketTimeout=", 0, 14))
                .collect(Collectors.joining("&"));
        return parts[0] + "?" + (options.isBlank() ? "" : options + "&")
                + "connectTimeout=" + connectMs + "&socketTimeout=" + socketMs;
    }
}
