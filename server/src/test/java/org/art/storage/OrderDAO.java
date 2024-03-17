package org.art.storage;

import org.art.client.dao.DAO;

public interface OrderDAO extends DAO<Order>{
	Iterable<Order> findByAccount(String account) throws Exception;
}
