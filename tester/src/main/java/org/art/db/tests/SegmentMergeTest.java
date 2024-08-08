package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//@Integration
public class SegmentMergeTest extends BaseIntegrationTest {
	
	private static final Logger logger = LoggerFactory.getLogger(BigSetTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				TestRunner.getAdapter().startPrimary("./tinysegments.properties");
				
				Client client = this.getClient().batch(false);
				
				String drop = "drop table if exists segmentTest";
				String create = "CREATE TABLE segmentTest (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255), count int)";
				String insert = "insert into segmentTest (id, first, last, count ) values (?, 'art', ?, ?)";
				String createIndex = "CREATE INDEX segmentTest ON segmentTest(count)";
				String query = "select * from segmentTest where count < 5000 order by count";
				
				client.execute(drop);
				client.execute(create);
				client.execute(createIndex);
				
				for (int idx = 0; idx < 2000; idx++) {
					client.execute(insert, idx, "last" + idx, idx);
				}
				
				DocumentSet set = client.executeQuery(query);
					
				if (set.size() != 2000) {
					logger.info("iips");
					set = client.executeQuery(query);
				}
				Assertions.assertThat(set.size() == 2000).isTrue();
				
				int count = 0;
				
				for (Document doc : set) {
					Assertions.assertThat(doc.get("count").equals(Long.valueOf(count))).isTrue();
					count++;
				}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}


}
