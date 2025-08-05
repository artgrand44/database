package org.art.db.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZipUtils {
	private static final Logger logger = LoggerFactory.getLogger(ZipUtils.class);
	
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
	
	public static void compressDirectory(File directory, File zip) throws Exception {
		try (ZipOutputStream stream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
			compressDirectory(directory, directory, stream);
		}
	}
	
	public static void compressDirectory(File root, File directory, ZipOutputStream stream) throws Exception {
		File[] files = directory.listFiles();
		
		if (files == null) {
			throw new Exception("There was an error zipping " + directory.getCanonicalPath());
		}
		
		String rootPath = root.getCanonicalPath();
		int	rootPathLength = rootPath.length();
		
		for (File file : files) {
			if (file.isDirectory()) {
				compressDirectory(root, file, stream);
			} else {
				try (BufferedInputStream fileStream = new BufferedInputStream(new FileInputStream(file))) {
					ZipEntry entry  = new ZipEntry(file.getCanonicalPath().substring(rootPathLength));

					logger.info("Zipping " + file.getCanonicalPath() + " to " + entry.getName());
					
					stream.putNextEntry(entry);

					byte[] bytes = new byte[1024];
					int length;

					while ((length = fileStream.read(bytes)) >= 0) {
						stream.write(bytes, 0, length);
					}

					stream.closeEntry();
				}
			}
		}		
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
	
	public static void decompressDirectoty(File zip, File target) throws Exception {
		try (ZipInputStream stream = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
			ZipEntry entry;
			
			while ((entry = stream.getNextEntry()) != null) {
				File file = new File(target, entry.getName());
				
				file.getParentFile().mkdirs();
				
				logger.info("Unzipping " + entry.getName() + " to " + file.getCanonicalPath());
				
				try (BufferedOutputStream fileStream = new BufferedOutputStream(new FileOutputStream(file))) {
					stream.transferTo(fileStream);
				}
			}
		}		
	}
}
