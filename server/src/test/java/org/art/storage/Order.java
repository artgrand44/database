package org.art.storage;

import java.util.ArrayList;
import java.util.List;

import org.art.db.client.dao.Entity;
import org.art.db.client.dao.Id;

@Entity(table="orders")
public class Order {

	@Id
	private String id;
	private String account;
	private List<Item> items;
	
	public Order(int account) {
		this.id =  String.valueOf(account);
		this.account = "ACC" + String.valueOf(account);
		
		this.items = new ArrayList<>();
		
		for (int idx = 0; idx < 10; idx++) {
			this.items.add(new Item(account * 10 + idx));
		}
	}
	
	public Order() {
		
	}

	public String getAccount() {
		return account;
	}

	public List<Item> getItems() {
		return items;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	public void setItems(List<Item> items) {
		this.items = items;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

}	

