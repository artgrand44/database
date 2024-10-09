package org.art.db.storage;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.art.db.common.CPUMeter;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RollingAverage;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

public class PrometheusPublisher implements MetricsPublisher {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(PrometheusPublisher.class);
	
	class GaugeWrapper {
		private final Gauge gauge;
		private final RollingAverage average; 
		
		GaugeWrapper(String name, String ...extra) {
			this(name, 1000, extra);			
		}
		
		GaugeWrapper(String name, long internal, String ...extra) {
			List<Tag> list = new ArrayList<>();
			
			list.add(Tag.of("Shard",    shard));
			list.add(Tag.of("Instance", localInstance));
			list.add(Tag.of("db",       database));
			
			for (int idx = 0; idx < extra.length - 1; idx += 2) {
				list.add(Tag.of(extra[idx], extra[idx + 1]));
			}
			
			this.average = new RollingAverage(name, internal);
			this.gauge = Gauge.builder(name, () -> average.getAverage()).tags(list).register(registry);
			
		}
		
		void increment() {
			this.average.logEvent();
		}
		
		void increment(long count) {
			this.average.logEvent(count);
		}

	}
	
	private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
	
	private static final long ONE_MEG = 1024 * 1024;
	
	private final String shard;
	private final String localInstance;
	private final String database;
	private final ScheduledFuture<?> timer;
	private final CPUMeter cpuMeter = new CPUMeter();
	private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
	
	private final Cache<String, Counter> counters =   Caffeine.newBuilder()
			                                                    .evictionListener((x, y, z) -> freeCounter((Counter)y))
			                                                    .maximumSize(1000)
			                                                    .build();

	private final Cache<String, GaugeWrapper> gauges =   Caffeine.newBuilder()
                                                                .evictionListener((x, y, z) -> freeGauge((GaugeWrapper)y))
                                                                .maximumSize(1000)
                                                                .build();


	private final static PrometheusPublisher instance = new PrometheusPublisher();
	
	
	private PrometheusPublisher() {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		this.shard = handler.getLocalShard();
		this.localInstance = handler.getLocalInstance().toString();
		this.database = Environment.getInstance().getProperty("org.art.db.database", "cluster");
		this.timer = Scheduler.DEFAULT.scheduleAtFixedRate(() -> this.timer(), 0, 500, TimeUnit.MILLISECONDS);
		
		Counter dbCounter = this.registry.counter("dbname", "db_name", this.database, "db", this.database);
		
		dbCounter.increment();
		this.logAlert("Starting...");
		this.logAlert("AAA...");
	}
	
	private Counter createCounter(String name, String ...extra) {
		List<Tag> list = new ArrayList<>();
		
		
		list.add(Tag.of("Shard",    this.shard));
		list.add(Tag.of("Instance", this.localInstance));
		list.add(Tag.of("db",       this.database));
		
		for (int idx = 0; idx < extra.length - 1; idx += 2) {
			list.add(Tag.of(extra[idx], extra[idx + 1]));
		}
		
		return this.registry.counter(name, list);
		
	}
	
	private void freeCounter(Counter counter) {
		this.registry.remove(counter);
	}
	
	private void freeGauge(GaugeWrapper wrapper) {
		this.registry.remove(wrapper.gauge);
	}
	
	public static PrometheusPublisher getInstance() {
		return instance;
	}
	
	@Override
	public void logAlert(String message) {
		counters.get("db_alert" + message, x -> createCounter(x, "Message", message)).increment();
	}

	@Override
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		
		gauges.get("db_client-count" + clientName,   x -> new GaugeWrapper(x, "ClientName", clientName, "Query", sql)).increment(calls);
		gauges.get("db_client-time" + clientName,    x -> new GaugeWrapper(x, "ClientName", clientName, "Query", sql)).increment(time);
		gauges.get("db_client-matches"  + clientName, x -> new GaugeWrapper(x, "ClientName", clientName, "Query", sql)).increment(matches);
	}

	@Override
	public void logDelete() {
		gauges.get("db_verb_delete", x -> new GaugeWrapper(x, "type", "Deletes")).increment();	
		gauges.get("db_tps", x -> new GaugeWrapper(x)).increment();
	}

	@Override
	public void logGet() {
		gauges.get("db_verb_select", x -> new GaugeWrapper(x, "type", "Selects")).increment();	
		gauges.get("db_tps", x -> new GaugeWrapper(x)).increment();
	}

	@Override
	public void logInsert() {
		gauges.get("db_verb_insert", x -> new GaugeWrapper(x, "type", "Inserts")).increment();	
		gauges.get("db_tps", x -> new GaugeWrapper(x)).increment();
	}

	@Override
	public void logSelect(String queryString, long time, int matches) {
		gauges.get("db_select-count-gauge" + queryString,   x -> new GaugeWrapper(x, "Query", queryString)).increment();
		gauges.get("db_select-time" + queryString,          x -> new GaugeWrapper(x, "Query", queryString)).increment(time);
		gauges.get("db_select-matches" + queryString,       x -> new GaugeWrapper(x, "Query", queryString)).increment(matches);
		
	}
	
	@Override
	public void logStatus(String status) {
	
	}
		

	@Override
	public void logUpdate() {
		gauges.get("db_verb_update", x -> new GaugeWrapper(x, "type", "Updates")).increment();	
		gauges.get("db_tps", x -> new GaugeWrapper(x)).increment();
		
	}
	
	public String scrape() {
		return this.registry.scrape();
	}
	
	public void start() {
		if (Environment.getInstance().getProperty("org.art.db.logPrometheus", "false").equalsIgnoreCase("true")) {
			Metrics.getInstance().addPublisher(this);
		}
	}
	
	public void stop() {
		this.timer.cancel(true);
	}
	
	private void timer() {
		long cpu = (long)(this.cpuMeter.getCPU() * 100);
		long memory = (long) (memoryBean.getHeapMemoryUsage().getUsed() / ONE_MEG);
		long maxMemory = (long) (memoryBean.getHeapMemoryUsage().getMax() / ONE_MEG);
		long percentMemory = (memory * 100) / maxMemory;
				
		gauges.get("db_cpu" + this.localInstance, x -> new GaugeWrapper(x, 10000)).increment(cpu);
		gauges.get("db_mem" + this.localInstance, x -> new GaugeWrapper(x, 10000)).increment(percentMemory);
		
	}
	
}
