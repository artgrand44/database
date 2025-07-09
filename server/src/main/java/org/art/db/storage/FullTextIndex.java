package org.art.db.storage;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.art.db.common.Comparison;
import org.art.db.common.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

public class FullTextIndex extends Index {
	private static final Logger logger = LoggerFactory.getLogger(FullTextIndex.class);
	
	private final static Set<String> stopwords = loadStopwords();

	
	public FullTextIndex(File file, String name) throws Exception {
		super(file, name, false);
	}
	
	public static Set<String> getTokens(Key key) {
		Set<String> result = new HashSet<>();
		String string = (String)key.getKeys()[0];
		StringBuffer builder = new StringBuffer();
		
		for (char chr : string.toCharArray()) {
			if (Character.isLetterOrDigit(chr)) {
				builder.append(chr);
			} else {
				String token = builder.toString().toLowerCase();
				
				if (!token.isEmpty() && !stopwords.contains(token)) {
					result.add(token);
				}
				
				builder.setLength(0);
			}
		}
		
		String token = builder.toString().toLowerCase();
		
		if (!token.isEmpty() && !stopwords.contains(token)) {
			result.add(token);
		}
		
		return result;
	}
	
	@Override
	public List<Key> getAll(Comparison comparison, Key key) throws Exception {
		Set<String> tokens = getTokens(key);
		Set<Key> matches = new HashSet<>();

		for (String token : tokens) {
			matches.addAll(super.getAll(Comparison.EQ, new Key(token)));
		}

		return new ArrayList<>(matches);
	}
	
	
	@Override
	public void insert(Key key, Key primaryKey) throws Exception {
		for (String token : getTokens(key)) {
			super.insert(new Key(token), primaryKey);
		}
	}

	private static Set<String> loadStopwords() {
		
		TypeFactory factory = TypeFactory.defaultInstance();
		JavaType type = factory.constructMapLikeType(HashMap.class, 
				                                     factory.constructType(String.class), 
				                                     factory.constructCollectionLikeType(HashSet.class, String.class));
		ObjectMapper mapper = new ObjectMapper();
		
		try (InputStream stream = FullTextIndex.class.getClassLoader().getResourceAsStream("./stopwords-iso.json")) {
			Map<String, Set<String>> map = mapper.readValue(stream, type);
			String language = Environment.getInstance().getPropertyWithDefault("org.art.db.lang");

			return map.get(language);
		} catch (Exception exc) {
			logger.info("There was an error loading the stopwords");
			return Collections.emptySet();
		}
	}
	
	@Override
	public void remove(Key key, Key primaryKey) throws Exception {
		for (String token : getTokens(key)) {
			super.remove(new Key(token), primaryKey);
		}
	}
	
	@Override
	public void update(Key oldKey, Key newKey, Key primaryKey) throws Exception {
		Set<String> oldTokens = getTokens(oldKey);
		Set<String> newTokens = getTokens(newKey);
		
		for (String token : oldTokens) {
			if (!newTokens.contains(token)) {
				super.remove(new Key(token), primaryKey);
			}
		}
		
		for (String token : newTokens) {
			if (!oldTokens.contains(token)) {
				super.insert(new Key(token), primaryKey);
			}
		}
	}	
	
}
