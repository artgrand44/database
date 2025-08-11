package org.art.storage;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryPounderTest extends BaseDelegationTest {
	private static final Logger logger = LoggerFactory.getLogger(QueryPounderTest.class);
	
	public QueryPounderTest() {
		super(org.art.db.tests.QueryPounderTest.class);		
	}
	
	public static void main(String[] args) {
		QueryPounderTest instance = new QueryPounderTest();
		List<DynamicTest> list =  instance.getTests();
		
		for (DynamicTest test : list) {
			try {
				test.getExecutable().execute();
			} catch (Throwable thr) {
				logger.info(thr.getMessage());
			}
		}
	}

}