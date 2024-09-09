package org.art.db.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.IOUtils;

public class ZipUtils {
	
	public static byte[] compress(byte[] input) {	
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
				GZIPOutputStream gstream = new GZIPOutputStream(bos)) {
			
			gstream.write(input);
			gstream.finish();

			return  bos.toByteArray();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public static void compress(File fromFile, File toFile) throws Exception {
		try (FileInputStream inStream = new FileInputStream(fromFile); 
				GZIPOutputStream outStream = new GZIPOutputStream(new FileOutputStream(toFile))) {
			IOUtils.copyLarge(inStream,outStream);
			outStream.finish();
		}		
	}
	
	public static ByteBuffer compress(ByteBuffer input) throws Exception {
		ByteBuffer dup = input.duplicate();
		int length = dup.remaining();
		byte[] bytes = new byte[length];
		
		dup.get(bytes);
		return ByteBuffer.wrap(ZipUtils.compress(bytes));
	}
	
	public static byte[] decompress(byte[] compressedData)  {
		try (ByteArrayInputStream bos = new ByteArrayInputStream(compressedData);
				GZIPInputStream gstream = new GZIPInputStream(bos)) {
			
			return IOUtils.toByteArray(gstream);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public static void decompress(File fromFile, File toFile) throws Exception {
		try (GZIPInputStream inStream = new GZIPInputStream(new FileInputStream(fromFile)); 
				FileOutputStream outStream = new FileOutputStream(toFile)) {
			IOUtils.copyLarge(inStream, outStream);
		}		
	}
	
}
