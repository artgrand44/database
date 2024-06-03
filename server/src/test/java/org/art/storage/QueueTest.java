package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueTest  extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(DAOTest.class);
	
	static interface Query {
		Iterable<Item> call(String key) throws Exception;
	}
	
	
	private Client client;
	
	public QueueTest() {
		
	}

	@BeforeEach
	public void beforeEach() throws Exception  {
		super.beforeEach();
		
		startInternal("./application.properties");
		
		this.client = new Client("cluster", "admin", "admin").batch(false);
		
	}
	
	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into queue (id, name, value) values (UID(), ?, ?)";
		
		client.execute(insert, "name" + idx, "vaue" + idx);
		
	}
	
	private DocumentSet lease() throws Exception {
		String lease = "update queue set owner='art' where id > ? and owner is null limit 1 returning *";
		
		return client.executeQuery(lease, GUID.MINIMUM);
		
	}
	@SuppressWarnings("unused")
	private void remove(Client client, GUID uid) throws Exception {
		String delete = "delete from queue where id = ?";
		
		client.execute(delete, uid);
		
	}

	private void setup(int count) throws Exception {
		String create = "CREATE TABLE queue (id guid NOT NULL PRIMARY KEY, name varchar(255), value varchar(255), owner varchar(255))";
		String createIndex = "CREATE INDEX index ON queue(id)";
		
		client.execute(create);
		client.execute(createIndex);
		
		for (int idx = 0; idx < count; idx++) {
			this.insert(client, idx);
		}
	}
	
	@Test
	public void test() {
		logger.info("Start handlerTest");

		try {
			this.setup(10);
			
			
			GUID prev = GUID.MINIMUM;
			
			for (int idx = 0; idx < 5; idx++) {
				DocumentSet list = this.lease();
				
				Assertions.assertThat(list != null).isTrue();
				Assertions.assertThat(list.size() == 1).isTrue();
				
				Document doc = list.iterator().next();
				GUID  guid = doc.get("id", GUID.class);
				
				Assertions.assertThat(doc.get("owner", String.class).equals("art")).isTrue();
				Assertions.assertThat(guid != null).isTrue();
				Assertions.assertThat(guid.compareTo(prev) > 0).isTrue();
				
				prev = guid;				
			}
		} catch (AssertionFailedError exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}		
	}			
}
