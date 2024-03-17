package org.art.db.storage;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Predicate;

import org.art.db.common.Configuration;
import org.art.db.common.Size;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Weigher;

public class ConfigurableCache<TKey, TValue> implements PropertyChangeListener, Weigher<TKey, TValue>, RemovalListener<TKey, TValue>   {
	private static final Logger logger = LoggerFactory.getLogger(ConfigurableCache.class);

	
	private final String maxProperty;
	private final Cache<TKey, TValue> cache;
		
	protected ConfigurableCache(String maxProperty) {
		this.maxProperty = maxProperty;
		
		Configuration config = Configuration.getInstance();
		String dflt = config.getDefault(maxProperty);
		Size size;
		
		try {
			size = new Size(Configuration.getInstance().getProperty(maxProperty));
		} catch (Exception exc) {
			size = new Size(dflt);
		}
		
		this.cache = Caffeine.newBuilder().weigher(this).evictionListener(this).maximumWeight(size.getSize()).build(); 
		
		Configuration.getInstance().addPropertyChangeListener(this);
	}
	
	
	TValue getIfPresent(TKey TKey) {
		return this.cache.getIfPresent(TKey);			
	}
	
	
	void invalidate(TKey TKey) {
		this.cache.invalidate(TKey);
	}
	
	void invalidate(Predicate<TKey> test) { 
		for (TKey key : this.cache.asMap().keySet()) {
			if (test.test(key)) {
				this.cache.invalidate(key);
			}
		}
	}
	
	void put(TKey TKey, TValue doc) {
		this.cache.put(TKey, doc);
	}
	
	@Override
	public void propertyChange(PropertyChangeEvent event) {

		String name = event.getPropertyName();
		String value = (String)event.getNewValue();
		
		if (name.equals(this.maxProperty) && value != null) {
			try {
				Policy<TKey, TValue> profile = this.cache.policy();
				
				profile.eviction().get().setMaximum(new Size(value).getSize());
			} catch (Exception exc) {
				logger.info("Exception changing " + name + " to " + value);
			}
		}
	}

	@Override
	public void onRemoval(@Nullable TKey key, @Nullable TValue value, RemovalCause cause) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public int weigh(TKey key, TValue value) {
		return 1;
	}

}

