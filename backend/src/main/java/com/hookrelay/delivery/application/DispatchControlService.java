package com.hookrelay.delivery.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Project-scoped and endpoint-scoped dispatch admission controls.
 *
 * <p>Tracks active claim counts and per-second rate limits in memory, bounded by configured
 * maximums. Callers must acquire before claiming a delivery and release after the attempt is
 * finalized (success, permanent failure, or dead-letter). All counters are approximate and
 * eventually consistent across application instances; the database remains the source of truth for
 * claim ownership.
 */
@Service
@ConditionalOnProperty(name = "hookrelay.delivery.dispatcher.enabled", havingValue = "true")
public class DispatchControlService {

  /** Active claim count per project. */
  private final ConcurrentHashMap<UUID, Integer> projectActiveClaims = new ConcurrentHashMap<>();

  /** Active claim count per endpoint. */
  private final ConcurrentHashMap<UUID, Integer> endpointActiveClaims = new ConcurrentHashMap<>();

  /** Recent start timestamps per project for sliding-window rate limiting. */
  private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Instant>> projectStartTimes =
      new ConcurrentHashMap<>();

  /** Recent start timestamps per endpoint for sliding-window rate limiting. */
  private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Instant>> endpointStartTimes =
      new ConcurrentHashMap<>();

  /**
   * Attempts to acquire dispatch capacity for a project/endpoint pair.
   *
   * @return true if capacity is available and counters were incremented; false if the project or
   *     endpoint is at its concurrency or rate limit.
   */
  public boolean tryAcquire(
      UUID projectId,
      UUID endpointId,
      int maxConcurrentPerProject,
      int maxConcurrentPerEndpoint,
      int ratePerProjectPerSecond,
      int ratePerEndpointPerSecond) {

    Instant now = Instant.now();

    int projectCount = projectActiveClaims.getOrDefault(projectId, 0);
    int endpointCount = endpointActiveClaims.getOrDefault(endpointId, 0);
    if (projectCount >= maxConcurrentPerProject || endpointCount >= maxConcurrentPerEndpoint) {
      return false;
    }

    if (!checkRateLimit(projectStartTimes.get(projectId), now, ratePerProjectPerSecond)
        || !checkRateLimit(endpointStartTimes.get(endpointId), now, ratePerEndpointPerSecond)) {
      return false;
    }

    projectActiveClaims.merge(projectId, 1, Integer::sum);
    endpointActiveClaims.merge(endpointId, 1, Integer::sum);
    recordStart(projectStartTimes, projectId, now);
    recordStart(endpointStartTimes, endpointId, now);

    return true;
  }

  /** Releases one unit of dispatch capacity for a project/endpoint pair. */
  public void release(UUID projectId, UUID endpointId) {
    projectActiveClaims.merge(projectId, -1, Integer::sum);
    endpointActiveClaims.merge(endpointId, -1, Integer::sum);
    if (projectActiveClaims.get(projectId) <= 0) {
      projectActiveClaims.remove(projectId);
    }
    if (endpointActiveClaims.get(endpointId) <= 0) {
      endpointActiveClaims.remove(endpointId);
    }
  }

  /** Returns the current active claim count for a project. */
  public int projectActiveClaims(UUID projectId) {
    return projectActiveClaims.getOrDefault(projectId, 0);
  }

  /** Returns the current active claim count for an endpoint. */
  public int endpointActiveClaims(UUID endpointId) {
    return endpointActiveClaims.getOrDefault(endpointId, 0);
  }

  private static boolean checkRateLimit(
      ConcurrentLinkedDeque<Instant> times, Instant now, int maxPerSecond) {
    if (times == null) return true;
    Duration window = Duration.ofSeconds(1);
    Instant cutoff = now.minus(window);
    while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
      times.pollFirst();
    }
    return times.size() < maxPerSecond;
  }

  private static void recordStart(
      ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Instant>> map, UUID id, Instant now) {
    timesMap(map, id).addLast(now);
    prune(map.get(id), now);
  }

  private static ConcurrentLinkedDeque<Instant> timesMap(
      ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Instant>> map, UUID id) {
    return map.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
  }

  private static void prune(ConcurrentLinkedDeque<Instant> times, Instant now) {
    Duration window = Duration.ofSeconds(1);
    Instant cutoff = now.minus(window);
    while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
      times.pollFirst();
    }
  }
}
