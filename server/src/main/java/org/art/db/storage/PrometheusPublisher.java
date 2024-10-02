package org.art.db.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.art.db.common.DiscoveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

public class PrometheusPublisher implements MetricsPublisher {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(PrometheusPublisher.class);
	
	private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
	
	
	
	private final String shard;
	private final String localInstance;
	
	
	private final Cache<String, Counter> counters =   Caffeine.newBuilder()
			                                                    .evictionListener((x, y, z) -> freeCounter((Counter)y))
			                                                    .maximumSize(1000)
			                                                    .build();

	private final static PrometheusPublisher instance = new PrometheusPublisher();
	
	
	private PrometheusPublisher() {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		this.shard = handler.getLocalShard();
		this.localInstance = handler.getLocalInstance().toString();
		
	}
	
	private Counter createCounter(String name, String ...extra) {
		List<Tag> list = new ArrayList<>();
		
		
		list.add(Tag.of("shard",    this.shard));
		list.add(Tag.of("instance", this.localInstance));
		
		for (int idx = 0; idx < extra.length - 2; idx += 2) {
			list.add(Tag.of(extra[idx], extra[idx + 1]));
		}
		
		return this.registry.counter(name, list);
		
	}
	
	private void freeCounter(Counter counter) {
		this.registry.remove(counter);
	}
	
	public static PrometheusPublisher getInstance() {
		return instance;
	}
	@Override
	public void logAlert(String message) {
		counters.get("alert", x -> createCounter(x, "msg", message)).increment();		
	}

	@Override
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		
		counters.get("client-count",   x -> createCounter(x, "name", clientName, "query", sql)).increment(calls);
		counters.get("client-time",    x -> createCounter(x, "name", clientName, "query", sql)).increment(time);
		counters.get("client-matches", x -> createCounter(x, "name", clientName, "query", sql)).increment(matches);
	}

	@Override
	public void logDelete() {
		counters.get("deletes", x -> createCounter(x)).increment();	
	}

	@Override
	public void logGet() {
		counters.get("gets", x -> createCounter(x)).increment();	
	}

	@Override
	public void logInsert() {
		counters.get("inserts", x -> createCounter(x)).increment();	
	}

	@Override
	public void logSelect(String queryString, long time, int matches) {
		counters.get("select-count",   x -> createCounter(x, "query", queryString)).increment();	
		counters.get("select-time",    x -> createCounter(x, "query", queryString)).increment(time);
		counters.get("select-matches", x -> createCounter(x, "query", queryString)).increment(matches);
		
	}
	
	@Override
	public void logStatus(String status) {
	
	}
		

	@Override
	public void logUpdate() {
		counters.get("updates", x -> createCounter(x)).increment();	
		
	}
	
	public String scrape() {
		return this.registry.scrape();
	}
	
	public void start() {
		if (Environment.getInstance().getProperty("org.art.db.logPrometheus", "false").equalsIgnoreCase("true")) {
			Metrics.getInstance().addPublisher(this);
		}
	}
	
}
