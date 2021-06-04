package bio.terra.workspace.common.utils;

import bio.terra.stairway.RetryRule;
import bio.terra.stairway.RetryRuleExponentialBackoff;
import bio.terra.stairway.RetryRuleFixedInterval;

/**
 * A selection of retry rule instantiators for use with Stairway flight steps. Each static method
 * creates a new object each time to prevent leakage across flights.
 */
public class RetryRules {

  private RetryRules() {}

  /**
   * Retry rule for steps interacting with GCP. If GCP is down, we don't know when it will be back,
   * so don't wait forever. Note that RetryRules can be re-used within but not across Flight
   * instances.
   */
  public static RetryRule cloud() {
    return new RetryRuleFixedInterval(10, 10);
  }

  /** Use for cloud operations that may take a couple of minutes to respond. */
  public static RetryRule cloudLongRunning() {
    return new RetryRuleExponentialBackoff(1, 8, 5 * 60);
  }

  /**
   * Use for a short exponential backoff retry, for operations that should be completable within a
   * few seconds.
   */
  public static RetryRule shortExponential() {
    return new RetryRuleExponentialBackoff(1, 8, 16);
  }

  /**
   * Buffer Retry rule settings. For Buffer Service, allow for long wait times. If the pool is
   * empty, Buffer Service may need time to actually create a new project.
   */
  public static RetryRule buffer() {
    return new RetryRuleExponentialBackoff(1, 5 * 60, 15 * 60);
  }
}