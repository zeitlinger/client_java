package io.prometheus.metrics.core.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import java.util.Iterator;
import java.util.ServiceLoader;
import javax.annotation.Nullable;

/**
 * SPI for plugging in alternative metric backends.
 *
 * <p>When a {@code MetricBackend} implementation is on the classpath and registered via {@link
 * ServiceLoader}, metric data points will delegate to it instead of using native storage. This
 * enables the Prometheus client API to record directly into an OpenTelemetry SDK pipeline.
 */
public interface MetricBackend {

  /**
   * Create a {@link CounterDataPoint} backed by this backend.
   *
   * @param metadata the metric name, help, and unit
   * @param labelNames the label names declared on the metric
   * @param labelValues the label values for this specific data point
   * @return a {@link CounterDataPoint} that delegates to the backend
   */
  CounterDataPoint createCounterDataPoint(
      MetricMetadata metadata, String[] labelNames, String[] labelValues);

  /**
   * Create a {@link GaugeDataPoint} backed by this backend.
   *
   * @param metadata the metric name, help, and unit
   * @param labelNames the label names declared on the metric
   * @param labelValues the label values for this specific data point
   * @return a {@link GaugeDataPoint} that delegates to the backend
   */
  GaugeDataPoint createGaugeDataPoint(
      MetricMetadata metadata, String[] labelNames, String[] labelValues);

  /**
   * Create a {@link DistributionDataPoint} backed by this backend for histogram metrics.
   *
   * @param metadata the metric name, help, and unit
   * @param labelNames the label names declared on the metric
   * @param labelValues the label values for this specific data point
   * @param classicUpperBounds the classic histogram bucket upper bounds (may be empty for
   *     native-only histograms)
   * @return a {@link DistributionDataPoint} that delegates to the backend
   */
  DistributionDataPoint createHistogramDataPoint(
      MetricMetadata metadata,
      String[] labelNames,
      String[] labelValues,
      double[] classicUpperBounds);

  /**
   * Whether the native Prometheus storage should be written in addition to the backend.
   *
   * <p>When {@code true} (the default), every metric operation writes to both the native Prometheus
   * adders and the backend. This keeps the {@code /metrics} scrape endpoint working. When {@code
   * false}, only the backend receives writes, which improves performance but means {@code
   * collect()} and {@code get()} on the Prometheus side return zero.
   */
  default boolean isDualWriteEnabled() {
    return true;
  }

  /**
   * Returns the {@link MetricBackend} discovered via {@link ServiceLoader}, or {@code null} if none
   * is on the classpath.
   */
  @Nullable
  static MetricBackend getInstance() {
    return Holder.INSTANCE;
  }

  /** Lazy holder for the ServiceLoader-discovered instance. */
  // This class is intentionally non-public; accessed only through getInstance().
  class Holder {
    @Nullable static final MetricBackend INSTANCE;

    static {
      Iterator<MetricBackend> it = ServiceLoader.load(MetricBackend.class).iterator();
      INSTANCE = it.hasNext() ? it.next() : null;
    }
  }
}
