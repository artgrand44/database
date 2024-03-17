package org.art.security;

import java.security.Key;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Utils {
		
	private final static Key aesKey = new SecretKeySpec("Bar12345Bar12345".getBytes(), "AES");
	    
	public static String encrypt(String text)  {		
		try {
			Cipher cipher = Cipher.getInstance("AES");
			cipher.init(Cipher.ENCRYPT_MODE, aesKey);

			byte[] encrypted = cipher.doFinal(text.getBytes());

			return Base64.getEncoder().encodeToString(encrypted);
		} catch (Exception exc) {
			throw new IllegalStateException("Encryption exception ", exc);
		}
	}
	
	public static String decrypt(String text) {
		try {
			Cipher cipher = Cipher.getInstance("AES");
			cipher.init(Cipher.DECRYPT_MODE, aesKey);

			byte[] plainText = cipher.doFinal(Base64.getDecoder().decode(text));

			return new String(plainText);
		} catch (Exception exc) {
			throw new IllegalStateException("Encryption exception ", exc);
		}
	}
		
}