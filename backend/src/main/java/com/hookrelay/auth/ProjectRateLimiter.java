package com.hookrelay.auth;

import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Atomic database-backed fixed window; one bounded row per authenticated project. */
@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class ProjectRateLimiter {
  private final JdbcTemplate jdbc;
  private final RateLimitProperties policy;

  public ProjectRateLimiter(JdbcTemplate jdbc, RateLimitProperties policy) {
    this.jdbc = jdbc;
    this.policy = policy;
  }

  /** Zero means allowed; positive values are Retry-After seconds. */
  public int acquire(UUID projectId) {
    var accepted =
        jdbc.query(
            """
            INSERT INTO project_request_limits(project_id, window_end, requests)
            VALUES (?, clock_timestamp() + (? * interval '1 second'), 1)
            ON CONFLICT (project_id) DO UPDATE SET
              requests = CASE WHEN project_request_limits.window_end <= clock_timestamp()
                         THEN 1 ELSE project_request_limits.requests + 1 END,
              window_end = CASE WHEN project_request_limits.window_end <= clock_timestamp()
                           THEN clock_timestamp() + (? * interval '1 second') ELSE project_request_limits.window_end END
            WHERE project_request_limits.window_end <= clock_timestamp() OR project_request_limits.requests < ?
            RETURNING requests
            """,
            (rs, row) -> rs.getInt(1),
            projectId,
            policy.windowSeconds(),
            policy.windowSeconds(),
            policy.requests());
    if (!accepted.isEmpty()) return 0;
    return jdbc.queryForObject(
        """
        SELECT greatest(1, ceil(extract(epoch FROM window_end - clock_timestamp())))::integer
        FROM project_request_limits WHERE project_id = ?
        """,
        Integer.class,
        projectId);
  }
}
