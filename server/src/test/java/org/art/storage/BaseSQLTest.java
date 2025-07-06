package org.art.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.sql.SQLHandler;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.IndexInfo;
import org.art.db.storage.Metadata;
import org.junit.jupiter.api.BeforeEach;

public abstract class BaseSQLTest extends BaseTest {
	private File dbDir = new File("./dbroot");
	
	
	@BeforeEach
	public void beforeEach() throws Exception {
		super.beforeEach();
		this.dbDir.mkdir();
		
		File[] files = this.dbDir.listFiles();
		
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		
	}
	protected Metadata registerMetadata(String name, String fields, String primaryKeys, String indices) throws Exception {
		SortedMap<String, ColumnInfo> fieldMap = new TreeMap<>();
		List<String> primaryKeyList = new ArrayList<>();
		List<IndexInfo>  indexList = new ArrayList<>();
		
		for (String field : fields.split(",")) {
			fieldMap.put(field, new ColumnInfo(String.class, false, true, null));
		}
		
		for (String field : primaryKeys.split(",")) {
			primaryKeyList.add(field);
		}

		if (indices.length() != 0) {
			for (String index : indices.split(",")) {
				String[] tokens = index.split("-");
				String indexName = tokens[0];
				boolean unique = tokens[1].equalsIgnoreCase("N");
				List<String> keys = new ArrayList<>();

				for (int idx = 2; idx < tokens.length; idx++) {
					keys.add(tokens[idx]);
				}

				IndexInfo info = new IndexInfo(indexName, unique, false, keys);
				indexList.add(info);
			}
		}
		
		Metadata info = new Metadata(name, primaryKeyList, fieldMap, indexList, false);
		
		info.store();
		
		return info;
	}
	
	void execute(String sql) throws Exception {
		SQLHandler.getInstance().execute(Client.WILDCARD, "", "admin", "admin", sql, Collections.emptyList());
	}
	
	void execute(String sql, Comparable<?>...args) throws Exception {
		
		List<Comparable<?>> list = new ArrayList<>();
		
		for (Comparable<?> arg : args) {
			list.add(arg);
		}
		
		SQLHandler.getInstance().execute(Client.WILDCARD, "", "admin", "admin", sql, list);
	}
	
	DocumentSet executeSelect(String sql) throws Exception {
		return SQLHandler.getInstance().executeQuery(Client.WILDCARD, "", "admin", "admin", sql, Collections.emptyList());
	}
	
	DocumentSet executeSelect(String sql, Comparable<?>...args) throws Exception {
		List<Comparable<?>> list = new ArrayList<>();
		
		for (Comparable<?> arg : args) {
			list.add(arg);
		}
		return SQLHandler.getInstance().executeQuery(Client.WILDCARD, "", "admin", "admin", sql, list);
	}
}
