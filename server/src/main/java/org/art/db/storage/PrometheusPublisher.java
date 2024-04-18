package org.art.db.storage;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.art.db.common.DiscoveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

public class PrometheusPublisher implements MetricsPublisher {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(PrometheusPublisher.class);
	
	private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
	
	private final String[] tags;
	
	private final Cache<String, CounterGroup> groups = Caffeine.newBuilder()
			                                                   .evictionListener((x, y, z) -> ((CounterGroup)y).free())
			                                                   .maximumSize(1000)
			                                                   .expireAfterAccess(1, TimeUnit.HOURS).build();

	private final Cache<String, Counter> counters =   Caffeine.newBuilder()
			                                                    .evictionListener((x, y, z) -> freeCounter((Counter)y))
			                                                    .maximumSize(1000)
			                                                    .expireAfterAccess(1, TimeUnit.HOURS)
			                                                    .build();

	private final static PrometheusPublisher instance = new PrometheusPublisher();
	
	
	private PrometheusPublisher() {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		this.tags = new String[] { "shard", handler.getlocalShard(), "instance", handler.getLocalInstance().toString() };		
	}
	
	private Counter createCounter(String name) {
		return this.registry.counter(name, this.tags);
	}
	
	private void freeCounter(Counter counter) {
		this.registry.remove(counter);
	}
	
	public static PrometheusPublisher getInstance() {
		return instance;
	}
	@Override
	public void logAlert(String message) {
		counters.get(message, x -> createCounter(x)).increment();		
	}

	@Override
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		CounterGroup group = groups.get(clientName, x -> new CounterGroup(clientName + "|" + sql, "time", "calls", "matches"));
		
		group.increment("calls");
		group.increment("time", time);
		group.increment("matches", matches);
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
		counters.get("insertys", x -> createCounter(x)).increment();	
	}

	@Override
	public void logSelect(String queryString, long time, int matches) {
		counters.get("selects", x -> createCounter(x)).increment();	
		
	}

	@Override
	public void logUpdate() {
		counters.get("updates", x -> createCounter(x)).increment();	
		
	}
	
	public String scrape() {
		return this.registry.scrape();
	}
	
	public void start() {
		Metrics.getInstance().addPublisher(this);
	}
	
	class CounterGroup extends HashMap<String, Counter> {
		private static final long serialVersionUID = 1L;

		CounterGroup(String groupName, String ... names) {
			for (String name : names) {
				this.put(name, registry.counter(groupName + "_" + name, tags));
			}
		}
		
		void increment(String name) {
			this.get(name).increment(1);
		}
		
		void increment(String name, long amount) {
			this.get(name).increment(amount);
		}
		
		void free() {
			for (Counter counter : this.values()) {
				registry.remove(counter);
			}
		}
	}
}
