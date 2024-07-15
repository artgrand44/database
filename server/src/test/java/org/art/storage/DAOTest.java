package org.art.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.art.db.client.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DAOTest  extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(DAOTest.class);
	
	static interface Query {
		Iterable<Item> call(String key) throws Exception;
	}
	
	private ItemDAO dao;
	private OrderDAO orderDAO;
	
	private Client client;
	
	public DAOTest() {
		
	}

	@BeforeEach
	public void beforeEach() throws Exception  {
		super.beforeEach();
		
		startInternal("./application.properties");
		
		this.client = this.getClient().batch(false);
		
		try {
			this.dao = client.getDAO(ItemDAO.class);
			this.orderDAO = client.getDAO(OrderDAO.class);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	Item createItem(int idx) {
		return new Item(idx);
	}
	
	private void setup(int count) throws Exception {
		String create = "CREATE TABLE items (orderNo VARCHAR(100) NOT NULL PRIMARY KEY, account varchar(255), date varchar(255))";
		String createIndex = "CREATE INDEX orderNp ON items(orderNo)";
		
		client.execute(create);
		client.execute(createIndex);
		
		for (int idx = 0; idx < count; idx++) {
			this.dao.insert(new Item(idx));
		}
	}
	
	@Test
	public void orderTest() {
		logger.info("Start handlerTest");

		try {

			String create = "CREATE TABLE orders (id VARCHAR(100) NOT NULL PRIMARY KEY, account varchar(255), items list)";
			String createIndex = "CREATE INDEX orders ON orders(account)";
			
			client.execute(create);
			client.execute(createIndex);
			
			for (int idx = 0; idx < 10; idx++) {
				this.orderDAO.insert(new Order(idx));
			}
			
			
			
			for (int idx = 0; idx < 10; idx++) {
				int count = 0;
				
				for (Order order : this.orderDAO.findByAccount("ACC" + String.valueOf(idx))) {
					count++;
					Assertions.assertThat(order.getItems().size() != 0).isTrue();
				}
				
				Assertions.assertThat(count == 1).isTrue();
				
			}
			
		} catch (AssertionFailedError exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}		
	}
	@Test
	public void basicTest() {
		logger.info("Start handlerTest");

		try {
			this.setup(10);
			
			for (int idx = 0; idx < 10; idx++) {
				Iterator<Item> items = this.dao.select("select * from items where orderNo = ?", String.valueOf(idx)).iterator();
				
				Assertions.assertThat(items.hasNext()).isTrue();
				
				Item item = items.next();
				
				Assertions.assertThat(item.getOrderNo().equals(String.valueOf(idx))).isTrue();
			}
			
		} catch (AssertionFailedError exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}

	
	private void checkOrderNumber(String name, Query query, String key, String[] expected)  {
		try {
			List<Item> items = new ArrayList<>();
			
			for (Item item : query.call(key)) {
				items.add(item);
			}
			
			Assertions.assertThat(expected.length == items.size()).isTrue();
			
			Collections.sort(items);
			
			for (int idx = 0; idx < expected.length; idx++) {
				Assertions.assertThat(expected[idx].equals(items.get(idx).getOrderNo())).isTrue();
			}
		} catch (Exception exc) {
			Assertions.fail(name + " got an exception" + exc.getMessage());
		}
	}
	

	private void checkAscending(Query query, String key) {
		try {
			List<Item> items = new ArrayList<>();
			
			for (Item item : query.call(key)) {
				items.add(item);
			}
			
			String prev = "orderNo";
			
			Assertions.assertThat(items != null).isTrue();
			Assertions.assertThat(items.isEmpty()).isTrue();

			for (Item item : items) {
				Assertions.assertThat(item.getOrderNo().compareTo(prev) > 0).isTrue();
				prev = item.getOrderNo();
			}
		} catch (Exception exc) {
			Assertions.fail(" got an exception" + exc.getMessage());
		}
	}
	
	private void checkDescending(Query query, String key) {
		try {
			List<Item> items = new ArrayList<>();
			
			for (Item item : query.call(key)) {
				items.add(item);
			}
			
			String prev = "orderNozzzzzzzzzzzzzzzzzzz";
			
			Assertions.assertThat(items != null).isTrue();
			Assertions.assertThat(items.isEmpty()).isTrue();

			for (Item item : items) {
				Assertions.assertThat(item.getOrderNo().compareTo(prev) < 0).isTrue();
				prev = item.getOrderNo();
			}
		} catch (Exception exc) {
			Assertions.fail(" got an exception" + exc.getMessage());
		}	
	}
	
	@Test
	public void test() {
		logger.info("Start test");

		try {
			this.setup(2);
			
			this.checkOrderNumber("--", x-> dao.findByOrderNo(x),                 "0",       new String[] { "0" });
			this.checkOrderNumber("> ", x-> dao.findByOrderNoGreaterThan(x),      "0",       new String[] { "1" });
			this.checkOrderNumber("< ", x-> dao.findByOrderNoLessThan(x),         "1",       new String[] { "0" });
			this.checkOrderNumber("<=", x-> dao.findByOrderNoLessThanEqual(x),    "1",       new String[] {"0", "1" });
			this.checkOrderNumber("ge", x-> dao.findByOrderNoGreaterThanEqual(x), "0",       new String[] {"0", "1" });
			this.checkOrderNumber("Is", x-> dao.findByOrderNoIs(x),               "0",       new String[] { "0" });
			this.checkOrderNumber("eq", x-> dao.findByOrderNoEquals(x),           "0",       new String[] { "0" });
			
			this.checkAscending(x-> dao.findByOrderNoOrderByOrderNoAsc(x), "agrand");
			this.checkDescending(x-> dao.findByOrderNoOrderByOrderNoDesc(x), "agrand");
			
			int count = 0;
			
			for (Item item : dao.findByOrderNo("0")) {				
				item.setAccount("Joe");
				dao.update(item);
				count++;
			}
			
			Assertions.assertThat(count == 1).isTrue();
			
			count = 0;
			
			for (Item item : dao.findByOrderNo("0")) {				
				Assertions.assertThat(item.getAccount().equals("Joe")).isTrue();
				item.setAccount("Sam");
				dao.update(item, "account");
				count++;
			}
			
			Assertions.assertThat(count == 1).isTrue();
			
			count = 0;
			
			
			for (Item item : dao.findByOrderNo("0")) {				
				Assertions.assertThat(item.getAccount().equals("Sam")).isTrue();
				count++;
			}
			
			Assertions.assertThat(count == 1).isTrue();
			
			count = 0;
			
			for (Item item : dao.findByOrderNo("0")) {				
				dao.delete(item);
			}
			
			count = 0;
			
			for (@SuppressWarnings("unused") Item item : dao.findByOrderNo("0")) {				
				count++;
			}
			
			Assertions.assertThat(count == 0).isTrue();
			
		} catch (AssertionFailedError exc) {
			logger.info("Assertion failed " + exc.getMessage());
			exc.printStackTrace();
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
		
}

