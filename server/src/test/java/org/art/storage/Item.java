package org.art.storage;

import java.util.Objects;

import org.art.client.dao.Column;
import org.art.client.dao.Entity;
import org.art.client.dao.Id;
import org.art.client.dao.Transient;

@Entity(table="items")
public class Item implements Comparable<Item> {
	
	@Id
	private String orderNo;
	private String account;
	private String theDate;
	private String ignore;
	
	public Item(int orderNumber) {
		this.orderNo = String.valueOf(orderNumber);
		this.account = "agrand";
		this.theDate = "today";
		
	}
	
	public Item() {
		
	}

	public String getOrderNo() {
		return orderNo;
	}

	public void setOrderNo(String orderNo) {
		this.orderNo = orderNo;
	}

	public String getAccount() {
		return account;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	@Column("date")
	public String getTheDate() {
		return theDate;
	}

	public void setTheDate(String date) {
		this.theDate = date;
	}

	@Override
	public int hashCode() {
		return Objects.hash(account, theDate, orderNo);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Item other = (Item) obj;
		return Objects.equals(account, other.account) && Objects.equals(theDate, other.theDate)
				&& Objects.equals(orderNo, other.orderNo);
	}

	@Override
	public String toString() {
		return "Item [orderNo=" + orderNo + ", account=" + account + ", date=" + theDate + "]";
	}

	@Override
	public int compareTo(Item that) {
		return this.orderNo.compareTo(that.orderNo);
	}

	@Transient
	public String getIgnore() {
		return ignore;
	}

	public void setIgnore(String ignore) {
		this.ignore = ignore;
	}

	
}

