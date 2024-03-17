package org.art.db.common;

import java.util.Collection;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.codec.digest.MurmurHash3.IncrementalHash32x86;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ShardFinder {
	
    private static final Logger logger = LoggerFactory.getLogger(ShardFinder.class);
    
    private TreeMap<Long, String> circle = new TreeMap<>();
     
    ShardFinder(Collection<String> shards) {
    	for (String shard : shards) {
    		this.addShard(shard);
    	}
    };
    
    
    void addShard(String shard) {
    	this.circle.put(hashString(shard.toString()), shard);       
    }

    String getShard(String key) {
    	long hash = hashString(key);
    	
    	if (this.circle.isEmpty()) {
        	logger.info("empty circle");
            return null;
        }
        
    	if (!this.circle.containsKey(hash)) {
            SortedMap<Long, String> tailMap = this.circle.tailMap(hash);
            hash = tailMap.isEmpty() ? this.circle.firstKey() : tailMap.firstKey();
        }
        
        return this.circle.get(hash);
    }

    private long hashString(String keyOrShard) {
    	IncrementalHash32x86 hasher = new IncrementalHash32x86();
    	byte[] bytes = keyOrShard.getBytes();
    	
    	hasher.start(0);
    	hasher.add(bytes, 0, bytes.length);
    	
    	return hasher.end();
    }


	void removeShard(String shard) {	
    	this.circle.remove(hashString(shard.toString()));
    }


	@Override
	public int hashCode() {
		return Objects.hash(circle);
	}


	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ShardFinder other = (ShardFinder) obj;
		return Objects.equals(circle, other.circle);
	}


	@Override
	public String toString() {
		return "ShardMap [circle=" + circle + "]";
	}
		
}
