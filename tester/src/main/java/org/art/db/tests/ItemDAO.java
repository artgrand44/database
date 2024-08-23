package org.art.db.tests;

import org.art.db.client.dao.DAO;

public interface ItemDAO extends DAO<Item>{
	
	Iterable<Item> findByAccountAndOrderNo(String account, String order) throws Exception;
	
	Iterable<Item> findByOrderNo(String order) throws Exception;
	Iterable<Item> findByOrderNoOrderByOrderNoAsc(String order) throws Exception;
	Iterable<Item> findByOrderNoOrderByOrderNoDesc(String order) throws Exception;
	Iterable<Item> findByOrderNoGreaterThan( String order) throws Exception;
	Iterable<Item> findByOrderNoLessThan( String order) throws Exception;
	Iterable<Item> findByOrderNoGreaterThanEqual( String order) throws Exception;
	Iterable<Item> findByOrderNoLessThanEqual(String order) throws Exception;
	Iterable<Item> findByOrderNoIs( String order) throws Exception;
	Iterable<Item> findByOrderNoEquals( String order)  throws Exception;
	Iterable<Item> findByOrderNoLike(String order);
	
	
}
