package io.prometheus.metrics.core.metrics;

import io.prometheus.metrics.config.MetricsProperties;
import io.prometheus.metrics.config.PrometheusProperties;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.exemplars.ExemplarSampler;
import io.prometheus.metrics.core.exemplars.ExemplarSamplerConfig;
import io.prometheus.metrics.model.registry.MetricType;
import io.prometheus.metrics.model.snapshots.Exemplar;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Gauge metric.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Gauge currentActiveUsers = Gauge.builder()
 *     .name("current_active_users")
 *     .help("Number of users that are currently active")
 *     .labelNames("region")
 *     .register();
 *
 * public void login(String region) {
 *     currentActiveUsers.labelValues(region).inc();
 *     // perform login
 * }
 *
 * public void logout(String region) {
 *     currentActiveUsers.labelValues(region).dec();
 *     // perform logout
 * }
 * }</pre>
 */
public class Gauge extends StatefulMetric<GaugeDataPoint, Gauge.DataPoint>
    implements GaugeDataPoint {

  @Nullable private final ExemplarSamplerConfig exemplarSamplerConfig;

  private Gauge(Builder builder, PrometheusProperties prometheusProperties) {
    super(builder);
    MetricsProperties[] properties = getMetricProperties(builder, prometheusProperties);
    boolean exemplarsEnabled =
        getConfigProperty(properties, MetricsProperties::getExemplarsEnabled);
    if (exemplarsEnabled) {
      exemplarSamplerConfig =
          new ExemplarSamplerConfig(prometheusProperties.getExemplarProperties(), 1);
    } else {
      exemplarSamplerConfig = null;
    }
  }

  @Override
  public void inc(double amount) {
    getNoLabels().inc(amount);
  }

  @Override
  public double get() {
    return getNoLabels().get();
  }

  @Override
  public void incWithExemplar(double amount, Labels labels) {
    getNoLabels().incWithExemplar(amount, labels);
  }

  @Override
  public void set(double value) {
    getNoLabels().set(value);
  }

  @Override
  public void setWithExemplar(double value, Labels labels) {
    getNoLabels().setWithExemplar(value, labels);
  }

  @Override
  public GaugeSnapshot collect() {
    return (GaugeSnapshot) super.collect();
  }

  @Override
  protected GaugeSnapshot collect(List<Labels> labels, List<DataPoint> metricData) {
    List<GaugeSnapshot.GaugeDataPointSnapshot> dataPointSnapshots = new ArrayList<>(labels.size());
    for (int i = 0; i < labels.size(); i++) {
      dataPointSnapshots.add(metricData.get(i).collect(labels.get(i)));
    }
    return new GaugeSnapshot(getMetadata(), dataPointSnapshots);
  }

  @Override
  public MetricType getMetricType() {
    return MetricType.GAUGE;
  }

  @Override
  protected DataPoint newDataPoint(String[] labelValues) {
    MetricBackend backend = MetricBackend.getInstance();
    if (backend != null) {
      GaugeDataPoint delegating =
          backend.createGaugeDataPoint(getMetadata(), labelNames, labelValues);
      boolean dualWrite = backend.isDualWriteEnabled();
      return new DataPoint(
          exemplarSamplerConfig != null ? new ExemplarSampler(exemplarSamplerConfig) : null,
          delegating,
          dualWrite);
    }
    if (exemplarSamplerConfig != null) {
      return new DataPoint(new ExemplarSampler(exemplarSamplerConfig));
    } else {
      return new DataPoint(null);
    }
  }

  static class DataPoint implements GaugeDataPoint {

    @Nullable
    private final ExemplarSampler exemplarSampler; // null if exemplarSamplerConfig is null

    @Nullable private final GaugeDataPoint delegate; // non-null when a MetricBackend is active
    private final boolean dualWrite;

    private DataPoint(@Nullable ExemplarSampler exemplarSampler) {
      this(exemplarSampler, null, true);
    }

    private DataPoint(
        @Nullable ExemplarSampler exemplarSampler,
        @Nullable GaugeDataPoint delegate,
        boolean dualWrite) {
      this.exemplarSampler = exemplarSampler;
      this.delegate = delegate;
      this.dualWrite = dualWrite;
    }

    private final AtomicLong value = new AtomicLong(Double.doubleToRawLongBits(0));

    @Override
    public void inc(double amount) {
      if (dualWrite) {
        value.updateAndGet(l -> Double.doubleToRawLongBits(Double.longBitsToDouble(l) + amount));
      }
      if (delegate != null) {
        delegate.inc(amount);
      }
      if (dualWrite && exemplarSampler != null) {
        exemplarSampler.observe(Double.longBitsToDouble(value.get()));
      }
    }

    @Override
    public void incWithExemplar(double amount, Labels labels) {
      if (dualWrite) {
        value.updateAndGet(l -> Double.doubleToRawLongBits(Double.longBitsToDouble(l) + amount));
      }
      if (delegate != null) {
        delegate.incWithExemplar(amount, labels);
      }
      if (dualWrite && exemplarSampler != null) {
        exemplarSampler.observeWithExemplar(Double.longBitsToDouble(value.get()), labels);
      }
    }

    @Override
    public void set(double value) {
      if (dualWrite) {
        this.value.set(Double.doubleToRawLongBits(value));
      }
      if (delegate != null) {
        delegate.set(value);
      }
      if (dualWrite && exemplarSampler != null) {
        exemplarSampler.observe(value);
      }
    }

    @Override
    public double get() {
      return Double.longBitsToDouble(value.get());
    }

    @Override
    public void setWithExemplar(double value, Labels labels) {
      if (dualWrite) {
        this.value.set(Double.doubleToRawLongBits(value));
      }
      if (delegate != null) {
        delegate.setWithExemplar(value, labels);
      }
      if (dualWrite && exemplarSampler != null) {
        exemplarSampler.observeWithExemplar(value, labels);
      }
    }

    private GaugeSnapshot.GaugeDataPointSnapshot collect(Labels labels) {
      // Read the exemplar first. Otherwise, there is a race condition where you might
      // see an Exemplar for a value that's not represented in getValue() yet.
      // If there are multiple Exemplars (by default it's just one), use the oldest
      // so that we don't violate min age.
      Exemplar oldest = null;
      if (exemplarSampler != null) {
        for (Exemplar exemplar : exemplarSampler.collect()) {
          if (oldest == null || exemplar.getTimestampMillis() < oldest.getTimestampMillis()) {
            oldest = exemplar;
          }
        }
      }
      return new GaugeSnapshot.GaugeDataPointSnapshot(get(), labels, oldest);
    }
  }

  public static Builder builder() {
    return new Builder(PrometheusProperties.get());
  }

  public static Builder builder(PrometheusProperties config) {
    return new Builder(config);
  }

  public static class Builder extends StatefulMetric.Builder<Builder, Gauge> {

    private Builder(PrometheusProperties config) {
      super(Collections.emptyList(), config);
    }

    @Override
    public Gauge build() {
      return new Gauge(this, properties);
    }

    @Override
    protected Builder self() {
      return this;
    }
  }
}
